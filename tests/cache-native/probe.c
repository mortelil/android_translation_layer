/* SPDX-License-Identifier: GPL-3.0-only */
/* No libc dependencies: the host-architecture ELF can be loaded by Bionic. */
int Java_org_atl_cachenative_TestActivity_answer(void *env, void *clazz)
{
    return 37;
}
