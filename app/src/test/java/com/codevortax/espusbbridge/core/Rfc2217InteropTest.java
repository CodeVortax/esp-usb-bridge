// SPDX-License-Identifier: MIT
package com.codevortax.espusbbridge.core;

import org.junit.Test;

/** Needs python3 with pySerial (pip install pyserial). CI installs it. */
public class Rfc2217InteropTest {
    @Test
    public void pySerialClientCanFlashThroughTheBridge() throws Exception {
        InteropHarness.runAll();
    }
}
