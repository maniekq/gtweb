package com.taskroo.service.security;

import com.taskroo.data.SessionDao;
import com.taskroo.domain.Role;
import com.taskroo.domain.User;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Priority(Priorities.AUTHORIZATION)
@Provider
public class SecurityContextFilter implements ContainerRequestFilter {

    private final SessionDao sessionDao;
    private String authenticationServiceUrl;

    @Inject
    public SecurityContextFilter(SessionDao sessionDao, @Named("authenticationServiceUrl") String authenticationServiceUrl) {
        this.sessionDao = sessionDao;
        this.authenticationServiceUrl = authenticationServiceUrl;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // Authorization: TaskRooAuth realm="taskroo@aetas.pl",tokenKey="=SessionIDString=",cnonce="uniqueValue"

        String authorizationHeader = requestContext.getHeaderString("Authorization");
        if (authorizationHeader == null) {
            requestContext.setSecurityContext(new TaskRooSecurityContext(null, null, authenticationServiceUrl));
            return;
        }

        Map<String, String> authHeaderMap = parseAuthorizationHeader(authorizationHeader);
        String tokenKey = authHeaderMap.get("tokenKey");

        User user = null;
        Session session = null;

        if (tokenKey != null && !tokenKey.isEmpty()) {
            session = sessionDao.findOneAndUpdateLastAccessedTime(tokenKey);

            if (null != session) {
                Set<Role> roles = new HashSet<>();
                roles.add(Role.USER);
                user = new User(session.getUserId(), roles);
            }
        }

        requestContext.setSecurityContext(new TaskRooSecurityContext(session, user, authenticationServiceUrl));
    }

    private Map<String, String> parseAuthorizationHeader(String authorizationHeader) {
        Map<String, String> authHeaderMap = new HashMap<>();

        authorizationHeader = authorizationHeader.replace("TaskRooAuth ", "");
        String[] authorizationHeaderElements = authorizationHeader.split(",");
        for (String authorizationHeaderElement : authorizationHeaderElements) {
            int equalIndex = authorizationHeaderElement.indexOf('=');
            String authHeaderElementKey = authorizationHeaderElement.substring(0, equalIndex).trim();
            String authHeaderElementValue = authorizationHeaderElement.substring(equalIndex+1).replaceAll("\"", "").trim();
            authHeaderMap.put(authHeaderElementKey, authHeaderElementValue);
        }
        return authHeaderMap;
    }
}