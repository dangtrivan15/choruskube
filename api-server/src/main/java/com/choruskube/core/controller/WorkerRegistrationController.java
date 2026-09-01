package com.choruskube.core.controller;

import com.choruskube.core.config.WorkerAuthFilter;
import com.choruskube.core.dto.WorkerRegisterRequest;
import com.choruskube.core.dto.WorkerRegisterResponse;
import com.choruskube.core.service.SingleFleetWorkerRegistrar;
import com.choruskube.core.service.WorkerRegistrar;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only API-server endpoint a Worker ever calls. Everything after registration is an outbound
 * long-poll to Temporal, which is what keeps the API server off the critical path of every step.
 *
 * <p>The route lives here, in core, so the published Worker image works against this server as it
 * does against any deployment that serves more than one Fleet — two implementations of one
 * endpoint could not both declare {@code /worker/register}, so what varies is the
 * {@link WorkerRegistrar} behind it, not the route.
 *
 * <p>No {@code @PreAuthorize}: like the {@code /internal/**} controllers, authentication is done
 * entirely in the filter and the registrar, not by method security.
 */
@RestController
@RequestMapping("/worker")
public class WorkerRegistrationController {

    private final WorkerRegistrar registrar;

    public WorkerRegistrationController(
            ObjectProvider<WorkerRegistrar> registrarProvider,
            @Value("${temporal.namespace}") String temporalNamespace,
            @Value("${temporal.task-queue}") String taskQueue,
            @Value("${worker.registration.token:}") String registrationToken) {
        this.registrar = registrarProvider.getIfAvailable(
                () -> new SingleFleetWorkerRegistrar(temporalNamespace, taskQueue, registrationToken));
    }

    @PostMapping("/register")
    public ResponseEntity<WorkerRegisterResponse> register(
            HttpServletRequest httpRequest, @Valid @RequestBody WorkerRegisterRequest request) {
        String token = (String) httpRequest.getAttribute(WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE);
        if (token == null || token.isBlank()) {
            // Defense in depth: WorkerAuthFilter guards /worker/** by raw-URI prefix, but Spring MVC
            // routes on the decoded path, so an encoded path (e.g. /%77orker/register) reaches here
            // unfiltered. Never assume a filter we cannot see from here actually ran.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(registrar.register(token, request));
    }
}
