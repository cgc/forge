package org.jupnp;

/**
 * Stub replacement for {@code org.jupnp.UpnpServiceConfiguration}.
 *
 * <p>The real jupnp library is excluded from the {@code forge-gui-teavm}
 * compile and TeaVM-compilation classpaths (see {@code pom.xml}).  TeaVM
 * encounters references to this interface inside compiled bytecode from
 * {@code forge-gui} ({@code IDeviceAdapter}, {@code FServerManager}) and
 * {@code forge-gui-mobile} ({@code GuiMobile}).  This empty stub satisfies
 * those references at TeaVM-compile time so the rest of the app can be
 * transpiled without needing the real jupnp jar.
 *
 * <p>At browser runtime, {@code FServerManager.startServer()} is never called
 * (multiplayer networking is disabled on the web target), so the UPnP code
 * paths are dead code that TeaVM will trim during tree-shaking.
 *
 * <p><b>Web impact:</b> None – UPnP is a local-network protocol and has no
 * browser equivalent.  Multiplayer via direct IP still works without UPnP
 * port-mapping; the user would need to set up port forwarding manually.
 */
public interface UpnpServiceConfiguration {
}
