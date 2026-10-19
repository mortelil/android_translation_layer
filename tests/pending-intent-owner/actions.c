// SPDX-License-Identifier: GPL-3.0-only
#include <jni.h>
#include <gio/gio.h>
JNIEXPORT jint JNICALL Java_org_atl_pendingprobe_NotificationProbe_actions(JNIEnv *env,jclass cls,jboolean activate)
{
 GApplication *app=g_application_get_default();
 if(!app)return -1;
 gchar **names=g_action_group_list_actions(G_ACTION_GROUP(app));int count=0;
 for(int i=0;names[i];i++)if(g_str_has_prefix(names[i],"pending-")){
  count++;if(activate)g_action_group_activate_action(G_ACTION_GROUP(app),names[i],NULL);
 }
 g_strfreev(names);return count;
}
