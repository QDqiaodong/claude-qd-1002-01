package com.electronics.assembly.common;

/**
 * 数据库锁等待超时（MySQL: Lock wait timeout exceeded；H2: Timeout trying to lock table）。
 *
 * <p>InnoDB / H2 默认只回滚触发超时的那一条语句而非整个事务，业务侧必须自行重试
 * 整个写操作：重新拿锁后状态机里的幂等分支会保证结果一致（不产生重复返修任务）。</p>
 *
 * <p>不继承 DataIntegrityViolationException，避免被唯一键冲突的 catch 分支吞掉。</p>
 */
public class LockTimeoutException extends RuntimeException {

    public LockTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
