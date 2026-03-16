package org.jupnp;

/**
 * Stub replacement for {@code org.jupnp.UpnpService}.
 *
 * <p>Used by {@code forge.gamemodes.net.server.FServerManager} to manage UPnP
 * port-mapping for the multiplayer server.  The UPnP code path is unreachable
 * from the web launcher ({@code FServerManager.startServer()} is never called),
 * so TeaVM will tree-shake it at compile time.  This stub exists only to
 * satisfy the type reference in the compiled {@code FServerManager} bytecode.
 *
 * <p>See {@link UpnpServiceConfiguration} for the companion interface.
 */
public interface UpnpService {
}
