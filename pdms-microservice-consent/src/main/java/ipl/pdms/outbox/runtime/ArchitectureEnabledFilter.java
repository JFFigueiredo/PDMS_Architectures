package ipl.pdms.outbox.runtime;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** The same local flag guards business HTTP and ordinary outbox work; management stays reachable. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ArchitectureEnabledFilter extends OncePerRequestFilter {
    private final RuntimeOutboxControl control;
    public ArchitectureEnabledFilter(RuntimeOutboxControl control) { this.control = control; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/api") || path.startsWith("/api/"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!control.enterBusiness()) {
            response.setStatus(503);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"architecture_disabled\"}");
            return;
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> { if (released.compareAndSet(false, true)) control.exitBusiness(); };
        try { chain.doFilter(request, response); }
        finally {
            if (!request.isAsyncStarted()) release.run();
            else {
                AsyncListener listener = new AsyncListener() {
                    public void onComplete(AsyncEvent event) { release.run(); }
                    // Keep the admission until servlet completion, including timeout/error dispatches.
                    public void onTimeout(AsyncEvent event) { }
                    public void onError(AsyncEvent event) { }
                    public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                };
                try { request.getAsyncContext().addListener(listener); }
                catch (IllegalStateException completed) { release.run(); }
            }
        }
    }
}
