package com.electronics.assembly.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.electronics.assembly.entity.Inspection;

public interface InspectionRepository extends JpaRepository<Inspection, Long> {

    List<Inspection> findByOrderId(Long orderId);

    List<Inspection> findByVerdict(String verdict);

    /** 未闭环的返修单 */
    List<Inspection> findByVerdictAndRepairDone(String verdict, Boolean repairDone);

    List<Inspection> findByOrderIdAndStepName(Long orderId, String stepName);
}
