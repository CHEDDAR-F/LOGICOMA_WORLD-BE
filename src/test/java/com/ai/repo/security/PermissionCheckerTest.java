package com.ai.repo.security;

import com.ai.repo.entity.Agent;
import com.ai.repo.entity.Comment;
import com.ai.repo.entity.Memory;
import com.ai.repo.entity.User;
import com.ai.repo.controller.AvatarController;
import com.ai.repo.controller.UserController;
import com.ai.repo.controller.UserSocialAccountController;
import com.ai.repo.dto.PasswordChangeRequest;
import com.ai.repo.dto.UserUpdateRequest;
import com.ai.repo.exception.AuthenticationException;
import com.ai.repo.exception.BusinessException;
import com.ai.repo.service.AgentService;
import com.ai.repo.service.CommentService;
import com.ai.repo.service.MemoryService;
import com.ai.repo.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MultipartFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionCheckerTest {

    @Mock
    private UserService userService;

    @Mock
    private AgentService agentService;

    @Mock
    private MemoryService memoryService;

    @Mock
    private CommentService commentService;

    @Mock
    private JoinPoint joinPoint;

    @Mock
    private ProceedingJoinPoint proceedingJoinPoint;

    @Mock
    private MethodSignature methodSignature;

    @Mock
    private HttpServletRequest request;

    private PermissionChecker permissionChecker;

    @BeforeEach
    void setUp() throws Exception {
        permissionChecker = new PermissionChecker();
        java.lang.reflect.Field field;
        field = PermissionChecker.class.getDeclaredField("userService");
        field.setAccessible(true);
        field.set(permissionChecker, userService);
        field = PermissionChecker.class.getDeclaredField("agentService");
        field.setAccessible(true);
        field.set(permissionChecker, agentService);
        field = PermissionChecker.class.getDeclaredField("memoryService");
        field.setAccessible(true);
        field.set(permissionChecker, memoryService);
        field = PermissionChecker.class.getDeclaredField("commentService");
        field.setAccessible(true);
        field.set(permissionChecker, commentService);
    }

    @Test
    void checkAuth_shouldPass_whenUserIdPresent() {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(1L);

            assertDoesNotThrow(() -> permissionChecker.checkAuth(joinPoint));
        }
    }

    @Test
    void checkAuth_shouldThrow_whenUserIdNull() {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(null);

            assertThrows(AuthenticationException.class, () -> permissionChecker.checkAuth(joinPoint));
        }
    }

    @Test
    void checkAuth_shouldThrow_whenNoRequestAttributes() {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes).thenReturn(null);

            assertThrows(AuthenticationException.class, () -> permissionChecker.checkAuth(joinPoint));
        }
    }

    @Test
    void checkHumanAuth_shouldPassForHumanAndRejectAgent() {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(1L);
            when(request.getAttribute("agentId")).thenReturn(null, 5L);

            assertDoesNotThrow(() -> permissionChecker.checkHumanAuth(joinPoint));
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> permissionChecker.checkHumanAuth(joinPoint));
            assertEquals(403, ex.getCode());
        }
    }

    @Test
    void checkHumanAuth_shouldRejectAnonymous() {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            assertThrows(AuthenticationException.class,
                    () -> permissionChecker.checkHumanAuth(joinPoint));
        }
    }

    @Test
    void humanAccountRoutesRequireHumanAuthentication() throws Exception {
        assertHumanOnly(UserController.class, "updateUser", HttpServletRequest.class, UserUpdateRequest.class);
        assertHumanOnly(UserController.class, "deleteUser", Long.class);
        assertHumanOnly(UserController.class, "logout", HttpServletRequest.class);
        assertHumanOnly(UserController.class, "changePassword", HttpServletRequest.class, PasswordChangeRequest.class);
        assertHumanOnly(AvatarController.class, "uploadAvatar", Long.class, MultipartFile.class, HttpServletRequest.class);
        assertHumanOnly(UserSocialAccountController.class, "getLinkedAccounts", HttpServletRequest.class);
        assertHumanOnly(UserSocialAccountController.class, "unlinkSocialAccount", HttpServletRequest.class, String.class);
    }

    private void assertHumanOnly(Class<?> controller, String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = controller.getDeclaredMethod(methodName, parameterTypes);
        assertNotNull(method.getAnnotation(RequireAuth.class));
        assertNotNull(method.getAnnotation(RequireHumanAuth.class));
    }

    @Test
    void humanAuthAspectRejectsAgentBeforeControllerMethod() {
        HumanAccountAction action = new HumanAccountAction();
        AspectJProxyFactory factory = new AspectJProxyFactory(action);
        factory.addAspect(permissionChecker);
        HumanAccountAction securedAction = factory.getProxy();
        MockHttpServletRequest agentRequest = new MockHttpServletRequest();
        agentRequest.setAttribute("userId", 1L);
        agentRequest.setAttribute("agentId", 5L);

        try {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(agentRequest));
            BusinessException ex = assertThrows(BusinessException.class, securedAction::run);
            assertEquals(403, ex.getCode());
            assertEquals(0, action.invocations);
            agentRequest.removeAttribute("agentId");
            securedAction.run();
            assertEquals(1, action.invocations);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    static class HumanAccountAction {
        int invocations;

        @RequireHumanAuth
        public void run() {
            invocations++;
        }
    }

    @Test
    void checkOwnership_shouldPass_whenUserIdMatches() throws Exception {
        RequireOwnership requireOwnership = mock(RequireOwnership.class);
        when(requireOwnership.resourceType()).thenReturn("memory");
        when(requireOwnership.idParam()).thenReturn("memoryId");

        Method testMethod = PermissionCheckerTest.class.getDeclaredMethod("dummyMethod", Long.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(testMethod);
        when(joinPoint.getArgs()).thenReturn(new Object[]{1L});

        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(1L);
            when(request.getAttribute("agentId")).thenReturn(null);

            Memory memory = new Memory();
            memory.setUserId(1L);
            when(memoryService.findById(1L)).thenReturn(memory);

            assertDoesNotThrow(() -> permissionChecker.checkOwnership(joinPoint, requireOwnership));
        }
    }

    @Test
    void checkOwnership_shouldThrow403_whenUserIdMismatch() throws Exception {
        RequireOwnership requireOwnership = mock(RequireOwnership.class);
        when(requireOwnership.resourceType()).thenReturn("memory");
        when(requireOwnership.idParam()).thenReturn("memoryId");

        Method testMethod = PermissionCheckerTest.class.getDeclaredMethod("dummyMethod", Long.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(testMethod);
        when(joinPoint.getArgs()).thenReturn(new Object[]{1L});

        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(1L);
            when(request.getAttribute("agentId")).thenReturn(null);

            Memory memory = new Memory();
            memory.setUserId(2L);
            when(memoryService.findById(1L)).thenReturn(memory);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> permissionChecker.checkOwnership(joinPoint, requireOwnership));
            assertEquals(403, ex.getCode());
        }
    }

    @Test
    void checkOwnership_shouldThrowAuth_whenBothNull() {
        RequireOwnership requireOwnership = mock(RequireOwnership.class);

        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(null);
            when(request.getAttribute("agentId")).thenReturn(null);

            assertThrows(AuthenticationException.class,
                    () -> permissionChecker.checkOwnership(joinPoint, requireOwnership));
        }
    }

    @SuppressWarnings("unused")
    public void dummyMethod(Long memoryId) {}

    @Test
    void checkAdmin_shouldPass_whenAdminActive() throws Throwable {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(1L);
            when(request.getAttribute("agentId")).thenReturn(null);

            User user = new User();
            user.setId(1L);
            user.setRole("ADMIN");
            user.setStatus("ACTIVE");
            when(userService.findById(1L)).thenReturn(user);
            when(proceedingJoinPoint.proceed()).thenReturn("ok");

            Object result = permissionChecker.checkAdmin(proceedingJoinPoint);
            assertEquals("ok", result);
        }
    }

    @Test
    void checkAdmin_shouldThrow401_whenUserIdNull() {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(null);

            assertThrows(AuthenticationException.class,
                    () -> permissionChecker.checkAdmin(proceedingJoinPoint));
        }
    }

    @Test
    void checkAdmin_shouldThrow403_whenAgentApiKey() {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(1L);
            when(request.getAttribute("agentId")).thenReturn(5L);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> permissionChecker.checkAdmin(proceedingJoinPoint));
            assertEquals(403, ex.getCode());
        }
    }

    @Test
    void checkAdmin_shouldThrow403_whenRegularUser() {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(1L);
            when(request.getAttribute("agentId")).thenReturn(null);

            User user = new User();
            user.setId(1L);
            user.setRole("USER");
            user.setStatus("ACTIVE");
            when(userService.findById(1L)).thenReturn(user);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> permissionChecker.checkAdmin(proceedingJoinPoint));
            assertEquals(403, ex.getCode());
        }
    }

    @Test
    void checkAdmin_shouldThrow403_whenUserNotFound() {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(1L);
            when(request.getAttribute("agentId")).thenReturn(null);
            when(userService.findById(1L)).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> permissionChecker.checkAdmin(proceedingJoinPoint));
            assertEquals(403, ex.getCode());
        }
    }

    @Test
    void checkAdmin_shouldThrow403_whenNotActive() {
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes)
                    .thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(1L);
            when(request.getAttribute("agentId")).thenReturn(null);

            User user = new User();
            user.setId(1L);
            user.setRole("ADMIN");
            user.setStatus("DISABLED");
            when(userService.findById(1L)).thenReturn(user);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> permissionChecker.checkAdmin(proceedingJoinPoint));
            assertEquals(403, ex.getCode());
        }
    }

    @Test
    void siblingAgentCannotEditAnotherAgentsComment() throws Exception {
        RequireOwnership ownership = mock(RequireOwnership.class);
        when(ownership.resourceType()).thenReturn("comment");
        when(ownership.idParam()).thenReturn("memoryId");
        Method method = PermissionCheckerTest.class.getDeclaredMethod("dummyMethod", Long.class);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[]{1L});
        try (MockedStatic<RequestContextHolder> holder = mockStatic(RequestContextHolder.class)) {
            holder.when(RequestContextHolder::getRequestAttributes).thenReturn(new ServletRequestAttributes(request));
            when(request.getAttribute("userId")).thenReturn(1L);
            when(request.getAttribute("agentId")).thenReturn(2L);
            com.ai.repo.entity.Comment comment = new com.ai.repo.entity.Comment();
            comment.setAgentId(3L);
            when(commentService.findById(1L)).thenReturn(comment);
            assertEquals(403, assertThrows(BusinessException.class,
                    () -> permissionChecker.checkOwnership(joinPoint, ownership)).getCode());
            verifyNoInteractions(agentService);
        }
    }
}
