package org.tiatesting.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the agent switches on ByteBuddy's experimental mode, so test classes compiled for a newer
 * Java than its ByteBuddy knows are still annotated, while leaving a user's own setting alone.
 */
class AgentTest {

    private String saved;

    /**
     * Save and clear the property.
     */
    @BeforeEach
    void setUp() {
        saved = System.getProperty(Agent.BYTE_BUDDY_EXPERIMENTAL);
        System.clearProperty(Agent.BYTE_BUDDY_EXPERIMENTAL);
    }

    /**
     * Restore the property.
     */
    @AfterEach
    void tearDown() {
        if (saved == null) {
            System.clearProperty(Agent.BYTE_BUDDY_EXPERIMENTAL);
        } else {
            System.setProperty(Agent.BYTE_BUDDY_EXPERIMENTAL, saved);
        }
    }

    @Test
    void experimentalModeIsSwitchedOnWhenUnset() {
        // given - the property is unset

        // when
        Agent.enableByteBuddyExperimentalMode();

        // then
        assertEquals("true", System.getProperty(Agent.BYTE_BUDDY_EXPERIMENTAL));
    }

    @Test
    void aUsersOwnSettingIsKept() {
        // given
        System.setProperty(Agent.BYTE_BUDDY_EXPERIMENTAL, "false");

        // when
        Agent.enableByteBuddyExperimentalMode();

        // then
        assertEquals("false", System.getProperty(Agent.BYTE_BUDDY_EXPERIMENTAL));
    }
}
