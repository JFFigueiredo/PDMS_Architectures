package ipl.pdms.faas;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Strips the OpenFaaS gateway prefix from the request path so the same REST API
 * works when called directly (/api/person) or via OpenFaaS (/function/pdms-faas/api/person).
 * <p>
 * When moving to Azure, the Azure Functions adapter receives the path directly; this filter
 * is effectively a no-op if the path does not start with /function/.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpenFaaSPathStrippingFilter implements Filter {

    private static final String PREFIX = "/function/pdms-faas";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String uri = req.getRequestURI();
        if (uri != null && uri.startsWith(PREFIX)) {
            String stripped = uri.substring(PREFIX.length());
            if (stripped.isEmpty()) {
                stripped = "/";
            }
            chain.doFilter(new PathStrippingRequestWrapper(req, stripped), response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private static final class PathStrippingRequestWrapper extends HttpServletRequestWrapper {
        private final String strippedUri;

        PathStrippingRequestWrapper(HttpServletRequest request, String strippedUri) {
            super(request);
            this.strippedUri = strippedUri;
        }

        @Override
        public String getRequestURI() {
            return strippedUri;
        }

        @Override
        public String getServletPath() {
            return strippedUri;
        }
    }
}
