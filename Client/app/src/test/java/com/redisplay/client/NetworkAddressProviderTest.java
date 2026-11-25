package com.redisplay.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import org.junit.Test;

public class NetworkAddressProviderTest {
    @Test
    public void selectsUsableWifiIpv4AndRejectsLoopbackAndLinkLocal() {
        assertEquals("192.168.137.86", NetworkAddressProvider.selectIpv4(Arrays.asList(
                "127.0.0.1", "fe80::1234", "169.254.1.8", "192.168.137.86")));
    }

    @Test
    public void returnsNullWithoutUsableIpv4() {
        assertNull(NetworkAddressProvider.selectIpv4(Arrays.asList("127.0.0.1", "fe80::1234")));
    }
}
