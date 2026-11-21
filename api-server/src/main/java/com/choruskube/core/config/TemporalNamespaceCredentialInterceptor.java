package com.choruskube.core.config;

import com.choruskube.core.credential.ControlPlaneTemporalTokens;
import com.choruskube.core.credential.TemporalTokenUnavailableException;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Attaches {@code authorization: Bearer <token>} to every Temporal call on the api-server's one
 * shared connection, chosen by the namespace the request names.
 *
 * <p>A request's namespace is only known once its message is sent, so this defers the wrapped
 * call's {@code start} until the first {@link ClientCall#sendMessage}: a request naming no
 * namespace (or a blank one) carries no header, same as a namespace for which the source reports
 * none. On {@link TemporalTokenUnavailableException} the wrapped call is never started on the
 * channel — the call fails {@code UNAUTHENTICATED} with the namespace only, never a token, in its
 * description.
 */
public class TemporalNamespaceCredentialInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final ControlPlaneTemporalTokens tokens;

    public TemporalNamespaceCredentialInterceptor(ControlPlaneTemporalTokens tokens) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new DeferredStartCall<>(next.newCall(method, callOptions), callOptions.getDeadline(), tokens);
    }

    /**
     * Queues {@code start}/{@code request} until {@code sendMessage} reveals the request's
     * namespace, so the authorization header can be computed from it before the wrapped call ever
     * starts.
     */
    private static final class DeferredStartCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {

        private final ClientCall<ReqT, RespT> delegate;
        private final Deadline deadline;
        private final ControlPlaneTemporalTokens tokens;

        private Listener<RespT> pendingListener;
        private Metadata pendingHeaders;
        private int pendingRequests;
        private boolean started;
        private boolean closed;

        DeferredStartCall(ClientCall<ReqT, RespT> delegate, Deadline deadline, ControlPlaneTemporalTokens tokens) {
            this.delegate = delegate;
            this.deadline = deadline;
            this.tokens = tokens;
        }

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
            this.pendingListener = responseListener;
            this.pendingHeaders = headers;
        }

        @Override
        public void request(int numMessages) {
            if (started) {
                delegate.request(numMessages);
            } else {
                pendingRequests += numMessages;
            }
        }

        @Override
        public void sendMessage(ReqT message) {
            if (closed) {
                return;
            }
            String namespace = namespaceOf(message);
            if (namespace != null && !namespace.isBlank()) {
                Optional<String> header;
                try {
                    header = tokens.authorizationHeader(namespace, maxWait());
                } catch (TemporalTokenUnavailableException e) {
                    failWithoutStarting(namespace);
                    return;
                }
                header.ifPresent(value -> pendingHeaders.put(AUTHORIZATION, value));
            }
            delegate.start(pendingListener, pendingHeaders);
            started = true;
            if (pendingRequests > 0) {
                delegate.request(pendingRequests);
                pendingRequests = 0;
            }
            delegate.sendMessage(message);
        }

        /** The call never reaches {@code delegate}, so nothing it holds is ever on the wire. */
        private void failWithoutStarting(String namespace) {
            closed = true;
            Listener<RespT> listener = pendingListener;
            Status status = Status.UNAUTHENTICATED.withDescription(
                    "no Temporal token available for namespace '" + namespace + "'");
            listener.onClose(status, new Metadata());
        }

        private Duration maxWait() {
            if (deadline == null) {
                return null;
            }
            return Duration.ofNanos(Math.max(0, deadline.timeRemaining(TimeUnit.NANOSECONDS)));
        }

        @Override
        public void halfClose() {
            if (started) {
                delegate.halfClose();
            }
        }

        @Override
        public void cancel(String message, Throwable cause) {
            closed = true;
            if (started) {
                delegate.cancel(message, cause);
            }
        }

        @Override
        public boolean isReady() {
            return started && delegate.isReady();
        }

        @Override
        public void setMessageCompression(boolean enabled) {
            if (started) {
                delegate.setMessageCompression(enabled);
            }
        }

        private static String namespaceOf(Object message) {
            if (!(message instanceof Message protoMessage)) {
                return null;
            }
            Descriptors.FieldDescriptor field =
                    protoMessage.getDescriptorForType().findFieldByName("namespace");
            if (field == null || field.getJavaType() != Descriptors.FieldDescriptor.JavaType.STRING) {
                return null;
            }
            Object value = protoMessage.getField(field);
            return value instanceof String s ? s : null;
        }
    }
}
