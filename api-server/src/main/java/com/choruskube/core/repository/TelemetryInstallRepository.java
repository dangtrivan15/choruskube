package com.choruskube.core.repository;

import com.choruskube.core.model.TelemetryInstall;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelemetryInstallRepository extends JpaRepository<TelemetryInstall, UUID> {}
