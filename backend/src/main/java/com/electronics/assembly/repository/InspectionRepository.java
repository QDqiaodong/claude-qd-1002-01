package com.electronics.assembly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.electronics.assembly.entity.Inspection;

import jakarta.persistence.LockModeType;

public interface InspectionRepository extends JpaRepository<Inspection, Long> {

    List<Inspection> findByOrderId(Long orderId);

    List<Inspection> findByVerdict(String verdict);

    /** 未闭环的返修单 */
    List<Inspection> findByVerdictAndRepairDone(String verdict, Boolean repairDone);

    List<Inspection> findByOrderIdAndStepName(Long orderId, String stepName);

    /** 同一工单同一工序的全部返修记录，按轮次倒序（最新一轮在前） */
    List<Inspection> findByOrderIdAndStepNameAndVerdictOrderByRepairRoundDescIdDesc(
            Long orderId, String stepName, String verdict);

    /** 按幂等键精确找某一轮返修记录（唯一约束 uk_insp_repair_round 的 Java 侧镜像） */
    Optional<Inspection> findByOrderIdAndStepNameAndRepairRoundAndVerdict(
            Long orderId, String stepName, Integer repairRound, String verdict);

    /** 某工单某工序已开过的最大返修轮次，没有则 0 */
    @Query("select coalesce(max(i.repairRound), 0) from Inspection i "
            + "where i.order.id = :orderId and i.stepName = :stepName and i.verdict = '返修'")
    int maxRepairRound(@Param("orderId") Long orderId, @Param("stepName") String stepName);

    /**
     * 悲观写锁读质检行：完成返修 / 复验先拿行锁，
     * 配合工单行锁把同一条返修记录上的并发操作串行化。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inspection i where i.id = :id")
    Optional<Inspection> findByIdForUpdate(@Param("id") Long id);
}
