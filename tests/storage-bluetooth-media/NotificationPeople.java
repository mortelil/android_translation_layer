// SPDX-License-Identifier: GPL-3.0-only
package org.atl.smallcompat;
import android.app.Notification;
import android.content.Context;
import java.util.Arrays;
/** API-23 metadata only: no notification posted, no real contacts. */
final class NotificationPeople {
    static void run(Context context) {
        Notification.Builder builder = new Notification.Builder(context);
        // Calling the method directly reproduces the original binary linkage failure.
        if (builder.addPerson("mailto:synthetic@example.invalid") != builder)
            throw new AssertionError("builder chaining");
        builder.addPerson("tel:+000000000").addPerson("mailto:synthetic@example.invalid").addPerson((String)null);
        String[] expected = {"mailto:synthetic@example.invalid", "tel:+000000000", "mailto:synthetic@example.invalid", null};
        Notification n = builder.build();
        if (n.extras == null || !Arrays.equals(expected, n.extras.getStringArray("android.people")))
            throw new AssertionError("legacy order, duplicates or null changed");
        n.extras.getStringArray("android.people")[0] = "changed";
        if (!Arrays.equals(expected, builder.build().extras.getStringArray("android.people")))
            throw new AssertionError("built array aliases builder list");
        Notification empty = new Notification.Builder(context).build();
        if (empty.extras == null || empty.extras.containsKey("android.people"))
            throw new AssertionError("empty builder or leaked people");
        try {
            if (!"android.people".equals(Notification.class.getField("EXTRA_PEOPLE").get(null)))
                throw new AssertionError("people key");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        System.out.println("NOTIFICATION_PEOPLE PASS");
    }
}
