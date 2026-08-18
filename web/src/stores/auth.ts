import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { ApiError, api, tokens, type ContributorStats, type User } from '@/lib/api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const stats = ref<ContributorStats | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  /** True until the stored token has been checked, so guards do not act early. */
  const resolved = ref(false)

  const isAuthenticated = computed(() => user.value !== null)
  const canVerify = computed(
    () => user.value?.role === 'researcher' || user.value?.role === 'admin',
  )
  const isAdmin = computed(() => user.value?.role === 'admin')

  async function restore() {
    if (!tokens.access) {
      resolved.value = true
      return
    }
    try {
      const response = await api.me()
      user.value = response.user
      stats.value = response.stats
    } catch {
      tokens.clear()
      user.value = null
    } finally {
      resolved.value = true
    }
  }

  async function signIn(email: string, password: string) {
    loading.value = true
    error.value = null
    try {
      const session = await api.login(email, password)
      tokens.set(session)
      user.value = session.user
      await refreshStats()
      return true
    } catch (err) {
      error.value =
        err instanceof ApiError ? err.message : 'Could not reach the server. Check it is running.'
      return false
    } finally {
      loading.value = false
    }
  }

  async function register(email: string, password: string, displayName: string) {
    loading.value = true
    error.value = null
    try {
      const session = await api.register(email, password, displayName)
      tokens.set(session)
      user.value = session.user
      await refreshStats()
      return true
    } catch (err) {
      error.value = err instanceof ApiError ? err.message : 'Could not create the account.'
      return false
    } finally {
      loading.value = false
    }
  }

  async function refreshStats() {
    try {
      const response = await api.me()
      user.value = response.user
      stats.value = response.stats
    } catch {
      // Stats are supplementary; a failure here must not break navigation.
    }
  }

  async function signOut() {
    try {
      await api.logout()
    } catch {
      // Logging out locally is what matters; the server token expires anyway.
    }
    tokens.clear()
    user.value = null
    stats.value = null
  }

  return {
    user,
    stats,
    loading,
    error,
    resolved,
    isAuthenticated,
    canVerify,
    isAdmin,
    restore,
    signIn,
    register,
    refreshStats,
    signOut,
  }
})
