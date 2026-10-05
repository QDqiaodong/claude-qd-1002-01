package com.electronics.assembly.common;

import java.util.function.Supplier;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 串行写操作的锁等待重试执行器。
 *
 * <p>返修闭环的所有写都按「工单悲观锁 → 行锁」串行化。极端并发下后到事务可能等锁超时
 * （MySQL {@code Lock wait timeout exceeded}、H2 {@code Timeout trying to lock table}），
 * 而 InnoDB/H2 只回滚超时语句不回滚事务，直接吞掉会让后续逻辑在无锁状态下继续跑。
 * 这里在检测到锁超时后用一个全新事务重试整个操作；重试时状态已被领先事务推进，
 * 业务方法的幂等分支保证最终结果一致。</p>
 */
@Component
public class LockRetryExecutor {

    /** 与生产 innodb_lock_wait_timeout 口径匹配的重试次数 */
    private static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate txTemplate;

    public LockRetryExecutor(PlatformTransactionManager txManager) {
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T execute(Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return txTemplate.execute(status -> action.get());
            } catch (RuntimeException ex) {
                if (!isLockTimeout(ex)) {
                    throw ex;
                }
                last = new LockTimeoutException("工单正被其他写操作占用（第 " + attempt
                        + " 次尝试），请稍后重试", ex);
                if (attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                }
            }
        }
        throw last;
    }

    private boolean isLockTimeout(Throwable ex) {
        for (Throwable cur = ex; cur != null; cur = cur.getCause()) {
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("Lock wait timeout")
                    || msg.contains("Timeout trying to lock")
                    || msg.contains("deadlock"))) {
                return true;
            }
            if (cur instanceof QueryTimeoutException
                    || cur instanceof CannotAcquireLockException) {
                return true;
            }
            if (cur instanceof ConcurrencyFailureException
                    || cur instanceof DataAccessResourceFailureException) {
                return msg != null && (msg.contains("lock") || msg.contains("timeout"));
            }
        }
        return false;
    }

    private void sleep(int attempt) {
        try {
            Thread.sleep(20L * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
