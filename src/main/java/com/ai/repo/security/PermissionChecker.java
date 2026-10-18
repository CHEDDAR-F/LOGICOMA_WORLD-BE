package com.ai.repo.security;

import com.ai.repo.entity.User;
import com.ai.repo.exception.AuthenticationException;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.service.*;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

@Slf4j
@Aspect
@Component
public class PermissionChecker {

    @Resource
    private UserService userService;

    @Resource
    private AgentService agentService;

    @Resource
    private MemoryService memoryService;

    @Resource
    private CommentService commentService;

    @Before("@annotation(com.ai.repo.security.RequireAuth)")
    public void checkAuth(JoinPoint joinPoint) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new AuthenticationException("Authentication required");
        }
        log.debug("Authentication check passed for user: {}", userId);
    }

    @Before("@annotation(com.ai.repo.security.RequireHumanAuth)")
    public void checkHumanAuth(JoinPoint joinPoint) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            throw new AuthenticationException("Authentication required");
        }
        if (getCurrentAgentId() != null) {
            throw new BusinessException(403, "Human user authentication required");
        }
    }

    @Around("@annotation(com.ai.repo.security.RequireAdmin)")
    public Object checkAdmin(ProceedingJoinPoint joinPoint) throws Throwable {
        Long userId = getCurrentUserId();
        Long agentId = getCurrentAgentId();
        if (userId == null) {
            throw new AuthenticationException("Authentication required");
        }
        if (agentId != null) {
            throw new BusinessException(403, "Admin access required");
        }
        User user = userService.findById(userId);
        if (user == null || !"ADMIN".equals(user.getRole())) {
            throw new BusinessException(403, "Admin access required");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException(403, "Admin access required");
        }
        log.debug("Admin check passed for user: {}", userId);
        return joinPoint.proceed();
    }

    @Before("@annotation(requireOwnership)")
    public void checkOwnership(JoinPoint joinPoint, RequireOwnership requireOwnership) {
        Long currentUserId = getCurrentUserId();
        Long currentAgentId = getCurrentAgentId();
        if (currentUserId == null && currentAgentId == null) {
            throw new AuthenticationException("Authentication required");
        }

        Long resourceId = getResourceId(joinPoint, requireOwnership.idParam());
        if (resourceId == null) {
            throw new BusinessException(400, "Resource ID not found in request");
        }

        Long resourceOwnerId = getResourceOwnerId(requireOwnership.resourceType(), resourceId);
        if (resourceOwnerId == null) {
            throw new BusinessException(404, "Resource not found");
        }

        boolean authorized;
        if ("comment".equalsIgnoreCase(requireOwnership.resourceType())) {
            authorized = currentAgentId != null && currentAgentId.equals(resourceOwnerId);
            if (!authorized && currentAgentId == null && currentUserId != null) {
                try {
                    com.ai.repo.entity.Agent agent = agentService.findById(resourceOwnerId);
                    authorized = agent != null && currentUserId.equals(agent.getUserId());
                } catch (Exception e) { /* agent not found */ }
            }
        } else {
            authorized = currentUserId != null && currentUserId.equals(resourceOwnerId);
        }

        if (!authorized) {
            log.warn("Ownership check failed: principal attempted to access resource {} owned by {}",
                    resourceId, resourceOwnerId);
            throw new BusinessException(403, "Access denied: you don't own this resource");
        }

        log.debug("Ownership check passed on resource {}", resourceId);
    }

    private Long getCurrentUserId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            Object userId = request.getAttribute("userId");
            return userId != null ? Long.valueOf(userId.toString()) : null;
        }
        return null;
    }

    private Long getCurrentAgentId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            Object agentId = request.getAttribute("agentId");
            return agentId != null ? Long.valueOf(agentId.toString()) : null;
        }
        return null;
    }

    private Long getResourceId(JoinPoint joinPoint, String idParam) {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            
            String pathVariable = (String) request.getAttribute(idParam);
            if (pathVariable != null) {
                try {
                    return Long.parseLong(pathVariable);
                } catch (NumberFormatException e) {
                    log.error("Invalid resource ID format: {}", pathVariable);
                    return null;
                }
            }
            
            String paramValue = request.getParameter(idParam);
            if (paramValue != null) {
                try {
                    return Long.parseLong(paramValue);
                } catch (NumberFormatException e) {
                    log.error("Invalid resource ID parameter: {}", paramValue);
                    return null;
                }
            }
        }
        
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();
        
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getName().equals(idParam) && args[i] instanceof Long) {
                return (Long) args[i];
            }
        }

        return null;
    }

    private Long getResourceOwnerId(String resourceType, Long resourceId) {
        try {
            switch (resourceType.toLowerCase()) {
                case "agent":
                    return agentService.findById(resourceId).getUserId();
                case "memory":
                    return memoryService.findById(resourceId).getUserId();
                case "comment":
                    return commentService.findById(resourceId).getAgentId();
                case "user":
                    User user = userService.findById(resourceId);
                    return user != null ? user.getId() : null;
                default:
                    log.error("Unknown resource type: {}", resourceType);
                    return null;
            }
        } catch (Exception e) {
            log.error("Error getting resource owner for {} {}: {}", resourceType, resourceId, e.getMessage());
            return null;
        }
    }
}
