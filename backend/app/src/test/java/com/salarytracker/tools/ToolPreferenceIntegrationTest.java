package com.salarytracker.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salarytracker.identity.CurrentUserResolver;
import com.salarytracker.integration.MySqlIntegrationTestSupport;
import com.salarytracker.platform.ConflictException;
import com.salarytracker.tools.ToolsModels.ToolPreference;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolPreferenceIntegrationTest extends MySqlIntegrationTestSupport {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void storesMortgageParametersPerUserAndReturnsLatestRevision() throws Exception {
        long first = createUser();
        long second = createUser();
        ToolPreferenceService firstService = service(first);
        ToolPreferenceService secondService = service(second);

        ToolPreference created = firstService.write("mortgage", payload("""
                {"mode":"single","single":{"principal":1200000,"annualRatePct":3.85,"years":30}}
                """), null);
        assertEquals("mortgage", created.tool());
        assertEquals(0, created.revision());
        assertEquals(1200000, created.payload().path("single").path("principal").asInt());

        ToolPreference updated = firstService.write("mortgage", payload("""
                {"mode":"combined","combo":{"commercialPrincipal":800000}}
                """), null);
        assertEquals(1, updated.revision(), "同一用户同一工具应原地更新并递增 revision");
        assertEquals("combined", updated.payload().path("mode").asText());

        // 另一个用户读不到、也不受影响
        ToolPreference isolated = secondService.read("mortgage");
        assertNull(isolated.payload(), "第二个用户不该看到第一个用户的参数");
        assertEquals(0, isolated.revision());
    }

    @Test
    void rejectsStaleRevisionWithConflictCarryingCurrentRevision() throws Exception {
        long userId = createUser();
        ToolPreferenceService service = service(userId);
        service.write("tax", payload("{\"monthlyIncome\":30000}"), null);
        service.write("tax", payload("{\"monthlyIncome\":35000}"), null);

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> service.write("tax", payload("{\"monthlyIncome\":40000}"), "0"));
        assertTrue(conflict.getMessage().contains("参数版本"), conflict.getMessage());

        ToolPreference current = service.read("tax");
        assertEquals(35000, current.payload().path("monthlyIncome").asInt());
    }

    @Test
    void onlyWhitelistedToolsAndJsonObjectsAreAccepted() throws Exception {
        long userId = createUser();
        ToolPreferenceService service = service(userId);

        assertThrows(IllegalArgumentException.class, () -> service.read("ledger"));
        assertThrows(IllegalArgumentException.class, () -> service.write("unknown", payload("{}"), null));
        assertThrows(IllegalArgumentException.class, () -> service.write("tax", payload("[1,2,3]"), null));
        assertThrows(IllegalArgumentException.class, () -> service.write("tax", payload("{}"), "not-a-revision"));
    }

    private ToolPreferenceService service(long userId) {
        CurrentUserResolver current = mock(CurrentUserResolver.class);
        when(current.id()).thenReturn(userId);
        return new ToolPreferenceService(jdbc, current, mapper);
    }

    private JsonNode payload(String json) throws Exception {
        return mapper.readTree(json);
    }

    private long createUser() {
        String username = "tool-" + UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(username,password_hash,nickname) VALUES(?,'!','工具测试')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }
}
