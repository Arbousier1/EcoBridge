package top.ellan.ecobridge.test.lifecycle;

import org.junit.jupiter.api.Test;
import top.ellan.ecobridge.application.bootstrap.AsmIntegrationLifecycle;
import top.ellan.ecobridge.application.bootstrap.CoreServiceLifecycle;
import top.ellan.ecobridge.application.bootstrap.InfrastructureLifecycle;
import top.ellan.ecobridge.application.bootstrap.PlatformIntegrationLifecycle;
import top.ellan.ecobridge.application.bootstrap.ReloadLifecycle;
import top.ellan.ecobridge.application.bootstrap.ShutdownLifecycle;
import top.ellan.ecobridge.application.lifecycle.LifecycleCatalog;
import top.ellan.ecobridge.application.lifecycle.LifecycleComponent;
import top.ellan.ecobridge.application.lifecycle.LifecyclePhase;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for lifecycle component registration and phase ordering.
 */
class LifecycleComponentTest {

    @Test
    void testAllComponentsRegistered() {
        List<LifecycleComponent> all = LifecycleCatalog.all();
        assertEquals(6, all.size(), "should have exactly 6 lifecycle components");
    }

    @Test
    void testStartupExcludesReloadAndShutdown() {
        List<LifecycleComponent> startup = LifecycleCatalog.startup();
        assertEquals(4, startup.size(), "startup should have 4 components (no RELOAD, no SHUTDOWN)");

        for (LifecycleComponent c : startup) {
            assertNotEquals(LifecyclePhase.RELOAD, c.phase());
            assertNotEquals(LifecyclePhase.SHUTDOWN, c.phase());
        }
    }

    @Test
    void testStartupPhaseOrderIsAscending() {
        List<LifecycleComponent> startup = LifecycleCatalog.startup();
        for (int i = 1; i < startup.size(); i++) {
            assertTrue(
                startup.get(i - 1).phase().ordinal() <= startup.get(i).phase().ordinal(),
                "startup components must be in ascending phase order"
            );
        }
    }

    @Test
    void testPhaseFlowStringIsNonEmpty() {
        String flow = LifecycleCatalog.startupPhaseFlow();
        assertNotNull(flow);
        assertFalse(flow.isEmpty());
        assertTrue(flow.contains("INFRASTRUCTURE"));
        assertTrue(flow.contains("ASM_INSTRUMENTATION"));
        assertTrue(flow.contains("CORE_SERVICES"));
        assertTrue(flow.contains("PLATFORM_INTEGRATION"));
    }

    @Test
    void testComponentFlowStringIsNonEmpty() {
        String flow = LifecycleCatalog.startupComponentFlow();
        assertNotNull(flow);
        assertFalse(flow.isEmpty());
    }

    @Test
    void testEachComponentHasUniquePhase() {
        // Components can share phases, but each phase should be represented
        List<LifecycleComponent> all = LifecycleCatalog.all();
        assertTrue(all.stream().map(LifecycleComponent::phase).distinct().count() >= 4,
            "at least 4 distinct phases should exist");
    }

    @Test
    void testInfrastructureLifecyclePhase() {
        assertEquals(LifecyclePhase.INFRASTRUCTURE, InfrastructureLifecycle.INSTANCE.phase());
        assertEquals("infrastructure", InfrastructureLifecycle.INSTANCE.componentName());
    }

    @Test
    void testAsmIntegrationLifecyclePhase() {
        assertEquals(LifecyclePhase.ASM_INSTRUMENTATION, AsmIntegrationLifecycle.INSTANCE.phase());
        assertEquals("asm-instrumentation", AsmIntegrationLifecycle.INSTANCE.componentName());
    }

    @Test
    void testCoreServiceLifecyclePhase() {
        assertEquals(LifecyclePhase.CORE_SERVICES, CoreServiceLifecycle.INSTANCE.phase());
        assertEquals("core-services", CoreServiceLifecycle.INSTANCE.componentName());
    }

    @Test
    void testPlatformIntegrationLifecyclePhase() {
        assertEquals(LifecyclePhase.PLATFORM_INTEGRATION, PlatformIntegrationLifecycle.INSTANCE.phase());
        assertEquals("platform-integration", PlatformIntegrationLifecycle.INSTANCE.componentName());
    }

    @Test
    void testReloadLifecyclePhase() {
        assertEquals(LifecyclePhase.RELOAD, ReloadLifecycle.INSTANCE.phase());
        assertEquals("reload", ReloadLifecycle.INSTANCE.componentName());
    }

    @Test
    void testShutdownLifecyclePhase() {
        assertEquals(LifecyclePhase.SHUTDOWN, ShutdownLifecycle.INSTANCE.phase());
        assertEquals("shutdown", ShutdownLifecycle.INSTANCE.componentName());
    }
}
