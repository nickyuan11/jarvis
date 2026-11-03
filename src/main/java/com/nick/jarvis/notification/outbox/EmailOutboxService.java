package com.nick.jarvis.notification.outbox;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nick.jarvis.entity.EmailOutbox;
import com.nick.jarvis.mapper.EmailOutboxMapper;
import com.nick.jarvis.support.JarvisTime;

/**
 * 邮件发件箱（Outbox 模式）。
 *
 * 流程：先把邮件写进 email_outbox 表 → 事务提交后立即在后台试发一次 → 失败了按指数退避，由定时任务重试。
 * 好处：
 * 1. 不会丢：邮件先落库，发送途中应用重启也还在表里；
 * 2. 不加重限流：失败后隔几分钟、间隔越来越长地重试，而不是连着猛试；
 * 3. 可排查：每封邮件的状态、次数、失败原因都在表里，不用翻日志。
 *
 * 投递语义是“至少一次”：如果邮件已经发出去了，但还没来得及改成 SENT 应用就崩了，重启后会再发一次。
 * 对提醒邮件来说，偶尔收到两封远比一封都收不到好。
 */
@Service
public class EmailOutboxService {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxService.class);
    private static final int BATCH_SIZE = 20;
    private static final int MAX_ERROR_LENGTH = 500;

    public enum Result {
        /** 发送成功 */
        SENT,
        /** 发送失败，已安排重试或已放弃 */
        FAILED,
        /** 没抢到（被别的线程或别的服务器处理了），什么也没做 */
        SKIPPED
    }

    private final EmailOutboxMapper mapper;
    private final SmtpMailer mailer;
    private final JarvisTime time;
    private final ApplicationEventPublisher events;

    public EmailOutboxService(EmailOutboxMapper mapper, SmtpMailer mailer, JarvisTime time,
                              ApplicationEventPublisher events) {
        this.mapper = mapper;
        this.mailer = mailer;
        this.time = time;
        this.events = events;
    }

    /**
     * 写入发件箱。只是插入一行，耗时约 1 毫秒；真正发送交给 onQueued 或定时任务。
     * 调用方在事务里时，这一行会和调用方的其他修改一起提交或回滚。
     */
    public EmailOutbox enqueue(Long userId, String to, String subject, String content) {
        EmailOutbox mail = new EmailOutbox();
        mail.setUserId(userId);
        mail.setToAddress(to);
        mail.setSubject(subject);
        mail.setContent(content);
        mail.setStatus(EmailOutbox.STATUS_PENDING);
        mail.setAttempts(0);
        // 去掉毫秒：MySQL 的 DATETIME 会把小数秒四舍五入，可能存成“下一秒”，
        // 那么紧接着的立即试发会因为 next_attempt_at <= 现在 不成立而抢不到，白等一分钟
        mail.setNextAttemptAt(time.now().withNano(0));
        mapper.insert(mail);
        // 只是“登记”一个事件，真正处理要等事务提交（见 onQueued）
        events.publishEvent(new EmailQueuedEvent(mail));
        return mail;
    }

    /**
     * 写入邮件的事务提交之后，马上在后台线程试发一次。
     *
     * 1. @TransactionalEventListener 默认在事务“提交之后”才执行。事务回滚了（比如提醒触发失败），
     *    邮件那一行也跟着回滚了，这里根本不会被调用，不会发出一封“数据库里不存在”的邮件；
     * 2. fallbackExecution = true：调用方没有开事务时（比如以后别处直接调 enqueue），不等提交，直接执行；
     * 3. @Async：SMTP 可能卡好几秒，不能占着提交事务的那个线程（提醒的定时任务线程）。
     *    监听器是 Spring 通过代理对象调用的，所以 @Async 在这里能生效，不是自调用。
     *
     * 就算这次没触发（比如提交后应用立刻崩了），邮件已经在表里，定时任务一分钟内会扫到它：
     * 立即试发只是为了快，定时任务才是兜底。
     */
    @Async
    @TransactionalEventListener(fallbackExecution = true)
    public void onQueued(EmailQueuedEvent event) {
        attempt(event.mail());
    }

    /**
     * 处理到期的待发送邮件，由定时任务调用。返回本轮成功发送的数量。
     */
    public int processDue() {
        if (!mailer.configured()) {
            return 0;
        }
        List<EmailOutbox> due = mapper.selectList(new LambdaQueryWrapper<EmailOutbox>()
                .eq(EmailOutbox::getStatus, EmailOutbox.STATUS_PENDING)
                .le(EmailOutbox::getNextAttemptAt, time.now())
                .orderByAsc(EmailOutbox::getNextAttemptAt)
                .orderByAsc(EmailOutbox::getId)
                .last("LIMIT " + BATCH_SIZE));

        int sent = 0;
        for (int i = 0; i < due.size(); i++) {
            Result result = attempt(due.get(i));
            if (result == Result.SENT) {
                sent++;
            } else if (result == Result.FAILED) {
                // 简单的熔断：163 限流限的是整个发件账号，不是某一封邮件。
                // 一封失败，同一轮里接着发剩下的大概率也失败，还会让限流持续更久，所以这一轮到此为止
                int skipped = due.size() - i - 1;
                if (skipped > 0) {
                    log.info("Email outbox: a send just failed, leaving {} due emails for the next round", skipped);
                }
                break;
            }
        }
        if (sent > 0) {
            log.info("Email outbox: sent {} emails", sent);
        }
        return sent;
    }

    /** 抢占并发送一封邮件 */
    Result attempt(EmailOutbox mail) {
        LocalDateTime now = time.now();
        if (!claim(mail, now)) {
            return Result.SKIPPED;
        }
        int attempts = mail.getAttempts() + 1;
        try {
            mailer.send(mail.getToAddress(), mail.getSubject(), mail.getContent());
        } catch (RuntimeException e) {
            onFailure(mail, attempts, now, e);
            return Result.FAILED;
        }
        // 标记成功放在 try 外面：如果是“改状态”这一步出错（比如数据库抖了一下），
        // 不能当成“发送失败”去重试，否则用户会收到重复的邮件
        markSent(mail, attempts);
        return Result.SENT;
    }

    /**
     * “抢占”一封邮件：只有更新成功（影响 1 行）的那次才去发送。
     *
     * 1. 条件里带上 status = PENDING 和原来的 attempts，相当于乐观锁：
     *    立即试发和定时任务、或者以后多台服务器，同时拿到同一封邮件，也只有一个能抢到；
     * 2. 抢到的同时把 next_attempt_at 推后 5 分钟（租约），并且在同一条 UPDATE 里完成。
     *    发送途中应用崩了，5 分钟后它自然又“到期”，会被重新扫到，不需要专门的恢复逻辑；
     * 3. attempts 在抢占时就 +1，所以“崩溃”也算一次尝试，不会无限重试下去。
     */
    private boolean claim(EmailOutbox mail, LocalDateTime now) {
        return mapper.update(null, new LambdaUpdateWrapper<EmailOutbox>()
                .set(EmailOutbox::getNextAttemptAt, now.plus(RetryPolicy.LEASE).withNano(0))
                .set(EmailOutbox::getAttempts, mail.getAttempts() + 1)
                .set(EmailOutbox::getUpdateTime, now)
                .eq(EmailOutbox::getId, mail.getId())
                .eq(EmailOutbox::getStatus, EmailOutbox.STATUS_PENDING)
                .eq(EmailOutbox::getAttempts, mail.getAttempts())
                .le(EmailOutbox::getNextAttemptAt, now)) == 1;
    }

    private void markSent(EmailOutbox mail, int attempts) {
        LocalDateTime now = time.now();
        mapper.update(null, new LambdaUpdateWrapper<EmailOutbox>()
                .set(EmailOutbox::getStatus, EmailOutbox.STATUS_SENT)
                .set(EmailOutbox::getSentAt, now)
                .set(EmailOutbox::getUpdateTime, now)
                .eq(EmailOutbox::getId, mail.getId()));
        if (attempts > 1) {
            log.info("Email #{} for user {} sent on attempt {}", mail.getId(), mail.getUserId(), attempts);
        }
    }

    private void onFailure(EmailOutbox mail, int attempts, LocalDateTime now, RuntimeException e) {
        String error = describe(e);
        LambdaUpdateWrapper<EmailOutbox> update = new LambdaUpdateWrapper<EmailOutbox>()
                .set(EmailOutbox::getLastError, error)
                .set(EmailOutbox::getUpdateTime, now)
                .eq(EmailOutbox::getId, mail.getId())
                .eq(EmailOutbox::getStatus, EmailOutbox.STATUS_PENDING);
        if (RetryPolicy.exhausted(attempts)) {
            update.set(EmailOutbox::getStatus, EmailOutbox.STATUS_FAILED);
            mapper.update(null, update);
            // 彻底放弃时才打印完整堆栈；中间的失败只记一行，避免限流期间日志刷屏
            log.error("Email #{} for user {} gave up after {} attempts", mail.getId(), mail.getUserId(), attempts, e);
        } else {
            LocalDateTime next = now.plus(RetryPolicy.delayAfter(attempts)).withNano(0);
            update.set(EmailOutbox::getNextAttemptAt, next);
            mapper.update(null, update);
            log.warn("Email #{} for user {} failed on attempt {}, will retry at {}: {}",
                    mail.getId(), mail.getUserId(), attempts, next, error);
        }
    }

    /**
     * 把异常写成一行，同时带上最外层和最里层：
     * 今天排查时外层是 MailAuthenticationException，真正的原因在最里层的 SocketTimeoutException。
     */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = e.getClass().getSimpleName();
        if (root != e) {
            text += " <- " + root.getClass().getSimpleName();
        }
        if (root.getMessage() != null) {
            text += ": " + root.getMessage();
        }
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }
}
