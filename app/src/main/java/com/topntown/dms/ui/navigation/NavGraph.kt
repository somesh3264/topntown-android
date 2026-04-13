package com.topntown.dms.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.topntown.dms.data.repository.AuthRepository
import com.topntown.dms.ui.components.OfflineNotice
import com.topntown.dms.ui.screens.beat.BeatScreen
import com.topntown.dms.ui.screens.bill.BillScreen
import com.topntown.dms.ui.screens.home.HomeScreen
import com.topntown.dms.ui.screens.login.LoginScreen
import com.topntown.dms.ui.screens.order.OrderScreen
import com.topntown.dms.ui.screens.payments.PaymentsScreen
import com.topntown.dms.ui.screens.profile.ProfileScreen
import com.topntown.dms.ui.screens.store.StoreOnboardingScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Route constants used across the app.
 */
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val HOME = "home"
    const val ORDER = "order"
    const val BEAT = "beat"
    const val PAYMENTS = "payments"
    const val BILL = "bill"
    const val PROFILE = "profile"
    const val STORE_ONBOARDING = "store_onboarding"
}

/**
 * Screens that show the bottom navigation bar. Splash and Login intentionally
 * omitted — they take the full screen with no chrome.
 */
private val bottomNavScreens = setOf(
    Routes.HOME,
    Routes.ORDER,
    Routes.BEAT,
    Routes.PAYMENTS,
    Routes.PROFILE
)

/** Screens that deserve the thin top app bar (logo + notifications bell). */
private val topAppBarScreens = bottomNavScreens

/**
 * Auth state computed once at app launch. A three-state enum instead of a
 * nullable boolean so the splash composable can render a spinner while we're
 * still resolving the session, rather than flashing the Login screen for
 * returning users.
 */
enum class AuthState { Checking, Authenticated, Unauthenticated }

@HiltViewModel
class AuthGateViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState.Checking)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = if (authRepository.isLoggedIn()) {
                AuthState.Authenticated
            } else {
                AuthState.Unauthenticated
            }
        }
    }

    /**
     * Clear Supabase session + DataStore, then invoke [onCleared] so the caller
     * can navigate. Exposed here (rather than in ProfileViewModel) so NavGraph
     * owns the "where to go after logout" concern in one place.
     */
    fun logout(onCleared: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            onCleared()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        topBar = {
            if (currentRoute in topAppBarScreens) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = "TopNTown",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    },
                    actions = {
                        IconButton(onClick = { /* notifications — wired in a later sprint */ }) {
                            Icon(
                                imageVector = Icons.Outlined.Notifications,
                                contentDescription = "Notifications"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            if (currentRoute in bottomNavScreens) {
                BottomNavBar(navController = navController)
            }
        }
    ) { innerPadding ->
        // OfflineNotice sits in a Column above the NavHost so the banner pushes
        // screen content down when visible instead of overlapping it. The
        // banner itself animates in/out, so when online this adds zero chrome.
        Column(modifier = Modifier.padding(innerPadding)) {
            OfflineNotice()
            NavHost(
                navController = navController,
                // Always boot into Splash; the splash decides where to go next based on
                // whether a valid Supabase session + profile snapshot exists.
                startDestination = Routes.SPLASH,
                modifier = Modifier.fillMaxSize()
            ) {

                composable(Routes.SPLASH) {
                    SplashRoute(
                        onAuthenticated = {
                            navController.navigate(Routes.HOME) {
                                // Nuke Splash from the back stack so the system-back
                                // button from Home doesn't bring us back here.
                                popUpTo(Routes.SPLASH) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                        onUnauthenticated = {
                            navController.navigate(Routes.LOGIN) {
                                popUpTo(Routes.SPLASH) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(Routes.LOGIN) {
                    LoginScreen(
                        onLoginSuccess = {
                            navController.navigate(Routes.HOME) {
                                // Clear everything below Home so back-press on Home
                                // exits the app rather than returning to Login.
                                popUpTo(0) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    )
                }

                composable(Routes.HOME) {
                    HomeScreen(navController = navController)
                }

                composable(Routes.ORDER) {
                    OrderScreen(navController = navController)
                }

                composable(Routes.BEAT) {
                    BeatScreen(navController = navController)
                }

                composable(Routes.PAYMENTS) {
                    PaymentsScreen(navController = navController)
                }

                composable(Routes.BILL) {
                    // Advance bill is distributor-wide, generated nightly — no orderId arg.
                    BillScreen()
                }

                composable(Routes.PROFILE) {
                    // AuthGateViewModel handles the actual signOut + DataStore clear; the
                    // nav call is deferred until that coroutine completes so we don't race
                    // a stale session back onto the Login screen.
                    val authGate: AuthGateViewModel = hiltViewModel()
                    ProfileScreen(
                        onLogout = {
                            authGate.logout {
                                navController.navigate(Routes.LOGIN) {
                                    popUpTo(0) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }

                composable(Routes.STORE_ONBOARDING) {
                    StoreOnboardingScreen(
                        onComplete = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

/**
 * Splash destination: shows a spinner while [AuthGateViewModel] resolves the
 * session, then fires one of the two callbacks exactly once. We use
 * `LaunchedEffect(state)` rather than observing in composition so the
 * navigation call happens inside a coroutine scope tied to this composable's
 * lifecycle.
 */
@Composable
private fun SplashRoute(
    onAuthenticated: () -> Unit,
    onUnauthenticated: () -> Unit,
    viewModel: AuthGateViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        when (state) {
            AuthState.Authenticated -> onAuthenticated()
            AuthState.Unauthenticated -> onUnauthenticated()
            AuthState.Checking -> Unit
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
