package com.electronics.assembly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.electronics.assembly.entity.SmtStep;

import jakarta.persistence.LockModeType;

public interface SmtStepRepository extends JpaRepository<SmtStep, Long> {

    List<SmtStep> findByOrderIdOrderById(Long orderId);

    Optional<SmtStep> findByOrderIdAndStepName(Long orderId, String stepName);

    List<SmtStep> findByStatus(String status);

    /**
     * 悲观写锁读工序游标：质检判返修打游标 / 复验推进游标时使用，
     * 与「开工 / 完工」在同一游标行上串行化，避免游标被覆盖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SmtStep s where s.order.id = :orderId and s.stepName = :stepName")
    Optional<SmtStep> findByOrderIdAndStepNameForUpdate(@Param("orderId") Long orderId,
                                                        @Param("stepName") String stepName);
}
