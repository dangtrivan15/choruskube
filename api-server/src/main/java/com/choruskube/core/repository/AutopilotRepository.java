package com.choruskube.core.repository;

import com.choruskube.core.model.Autopilot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AutopilotRepository extends JpaRepository<Autopilot, UUID> {}
