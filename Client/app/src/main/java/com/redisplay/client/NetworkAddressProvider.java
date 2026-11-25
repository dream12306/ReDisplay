package com.redisplay.client;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

final class NetworkAddressProvider {
    static String currentWifiIpv4(Context context) {
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        Network network = manager.getActiveNetwork();
        if (network == null) return null;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null;
        }
        LinkProperties properties = manager.getLinkProperties(network);
        if (properties == null) return null;
        List<String> candidates = new ArrayList<>();
        for (LinkAddress address : properties.getLinkAddresses()) {
            candidates.add(address.getAddress().getHostAddress());
        }
        return selectIpv4(candidates);
    }

    static String selectIpv4(List<String> candidates) {
        if (candidates == null) return null;
        for (String candidate : candidates) {
            try {
                InetAddress address = InetAddress.getByName(candidate);
                if (address instanceof Inet4Address
                        && !address.isLoopbackAddress()
                        && !address.isLinkLocalAddress()
                        && !address.isAnyLocalAddress()) {
                    return address.getHostAddress();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private NetworkAddressProvider() {}
}
