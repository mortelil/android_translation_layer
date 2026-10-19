// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providertest;
public class HighOwner extends Owner {
 static boolean ready, disabled;
 public boolean onCreate(){ready=true;return true;}
}
