package dev.ccdesk.nativeapp.core

import org.junit.Assert.*
import org.junit.Test

class RemoteComputersTest {
    @Test fun localPhoneIsExcludedByIdentityWithoutHidingSameNamedPreparingComputersOrOfflineHistory() {
        val phone = Device("phone", "CC Android Tunnel", ready = false)
        val computer = Device("computer", "CC Android Tunnel", ready = false)
        val offline = Device("offline", "CC Android Tunnel")
        val phoneSession = Session("phone-history", device = phone)
        val state = WorkbenchState(status = "online", localDeviceId = phone.id,
            devices = listOf(phone, computer), sessions = listOf(phoneSession, Session("saved", device = offline)))
        val groups = remoteComputers(state)
        assertEquals(listOf("computer", "offline"), groups.map { it.device.id })
        assertEquals(RemoteComputerStatus.PREPARING, groups[0].status)
        assertEquals(RemoteComputerStatus.OFFLINE, groups[1].status)
        assertEquals("saved", groups[1].sessions.single().id)
        assertFalse(state.isClientSession(phoneSession))
        assertTrue(state.isClientSession(Session("computer-session", device = computer)))
        assertTrue(state.isClientSession(Session("offline-session", device = offline)))
        assertEquals(listOf("offline"), remoteComputers(state.copy(devices = emptyList())).map { it.device.id })
        assertFalse("A phone is excluded, not reclassified as the server", phone.id in state.serverDeviceIds())
    }

    @Test fun clientControlsRequireAnExistingIdentifiedSessionAndExcludeEveryServerIdentitySource() {
        val computer = Device("desktop", "Computer")
        val state = WorkbenchState(serverDeviceId = "known-server",
            devices = listOf(Device("live-server", server = true)),
            sessions = listOf(Session("history", device = Device("history-server", server = true))))
        assertTrue("An offline computer session is still a client session", state.isClientSession(Session("client", device = computer)))
        assertFalse(state.isClientSession(Session("", device = computer)))
        assertFalse(state.isClientSession(Session("  ", device = computer)))
        assertFalse(state.isClientSession(Session("missing-device")))
        assertFalse(state.isClientSession(Session("blank-device", device = Device("  "))))
        for (id in listOf("known-server", "live-server", "history-server")) {
            assertFalse("Server identity from any snapshot must exclude $id", state.isClientSession(Session("candidate", device = Device(id))))
        }
        assertFalse("The candidate's own server flag is authoritative even before it enters state", state.isClientSession(Session("new-server", device = Device("unlisted", server = true))))
    }

    @Test fun sameNamedComputersStaySeparateAndOfflineHistorySurvivesPresenceUpdates() {
        val first = Device("first", "Desk", ready = true)
        val second = Device("second", "Desk")
        val state = WorkbenchState(status = "online", devices = listOf(first), sessions = listOf(
            Session("one", device = first), Session("two", device = second), Session("three", device = second)))
        val groups = remoteComputers(state)
        assertEquals(listOf("first", "second"), groups.map { it.device.id })
        assertEquals(listOf("one"), groups[0].sessions.map { it.id })
        assertEquals(setOf("two", "three"), groups[1].sessions.map { it.id }.toSet())
        assertTrue(groups.all { it.duplicateName })
        assertEquals(RemoteComputerStatus.ONLINE, groups[0].status)
        assertEquals(RemoteComputerStatus.OFFLINE, groups[1].status)
        assertEquals(3, remoteComputers(state.copy(devices = emptyList())).sumOf { it.sessions.size })
    }

    @Test fun serverAndUnidentifiedSessionsAreNeverPresentedAsComputers() {
        val server = Device("server", "Server", server = true)
        val knownServer = Device("known-server", "Previously connected server")
        val sessionServer = Device("session-server", "Server from history", server = true)
        val computer = Device("desktop", "Computer", ready = false)
        val state = WorkbenchState(status = "online", serverDeviceId = knownServer.id,
            devices = listOf(server, computer), sessions = listOf(
                Session("a", device = server), Session("b", device = knownServer),
                Session("c", device = sessionServer), Session("d", device = null)))
        val group = remoteComputers(state).single()
        assertEquals("desktop", group.device.id)
        assertTrue(group.sessions.isEmpty())
        assertEquals(RemoteComputerStatus.PREPARING, group.status)
    }

    @Test fun disconnectedWorkbenchCannotClaimStaleDevicesAreOnline() {
        val state = WorkbenchState(status = "offline", devices = listOf(Device("desktop", "Computer", ready = true)))
        assertEquals(RemoteComputerStatus.DISCONNECTED, remoteComputers(state).single().status)
    }

    @Test fun searchPreservesComputerIdentityAndPinsSortWithinEachComputer() {
        val computer = Device("desktop", "Current name", ready = true)
        val state = WorkbenchState(status = "online", devices = listOf(computer), pins = listOf("older"), sessions = listOf(
            Session("newer", "New task", "/workspace/tunnel", computer.copy(name = "Old name"), updatedAt = "2026-11-15"),
            Session("older", "Pinned task", "/workspace/other", computer, updatedAt = "2026-11-14")))
        assertEquals(listOf("older", "newer"), remoteComputers(state, "Current name").single().sessions.map { it.id })
        assertEquals(listOf("newer"), remoteComputers(state, "tunnel").single().sessions.map { it.id })
        assertTrue(remoteComputers(state, "does not exist").isEmpty())
    }
}
