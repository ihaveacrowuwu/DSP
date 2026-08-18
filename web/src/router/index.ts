import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/sign-in',
    name: 'sign-in',
    component: () => import('@/views/SignInView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    redirect: '/reefs',
  },
  {
    path: '/reefs',
    name: 'reefs',
    component: () => import('@/views/ReefMapView.vue'),
    meta: { requiresVerifier: true },
  },
  {
    path: '/queue',
    name: 'queue',
    component: () => import('@/views/QueueView.vue'),
    meta: { requiresVerifier: true },
  },
  {
    path: '/sightings',
    name: 'sightings',
    component: () => import('@/views/SightingsView.vue'),
  },
  {
    path: '/sightings/:id',
    name: 'sighting',
    component: () => import('@/views/SightingDetailView.vue'),
  },
  {
    path: '/operations',
    name: 'operations',
    component: () => import('@/views/OperationsView.vue'),
    meta: { requiresAdmin: true },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // One-time token check before the first guarded navigation.
  if (!auth.resolved) await auth.restore()

  if (to.meta.public) {
    return auth.isAuthenticated && to.name === 'sign-in' ? { name: 'sightings' } : true
  }
  if (!auth.isAuthenticated) {
    return { name: 'sign-in', query: { next: to.fullPath } }
  }
  // Contributors have no business in review or operations screens; send them
  // somewhere useful rather than showing a bare error.
  if (to.meta.requiresVerifier && !auth.canVerify) {
    return { name: 'sightings' }
  }
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    return { name: 'sightings' }
  }
  return true
})

export default router
