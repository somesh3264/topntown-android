package com.topntown.dms.ui.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Declarative entry for each tab of the bottom bar. We keep separate filled /
 * outlined icons because Material 3's NavigationBarItem doesn't toggle icon
 * style automatically — only the indicator pill background changes.
 */
data class BottomNavItem(
    val label: String,
    val route: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val navItems = listOf(
    BottomNavItem(
        label = "Home",
        route = Routes.HOME,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    ),
    BottomNavItem(
        label = "Order",
        route = Routes.ORDER,
        selectedIcon = Icons.Filled.AssignmentTurnedIn,
        unselectedIcon = Icons.Outlined.AssignmentTurnedIn
    ),
    BottomNavItem(
        label = "Beat",
        route = Routes.BEAT,
        selectedIcon = Icons.Filled.LocalShipping,
        unselectedIcon = Icons.Outlined.LocalShipping
    ),
    BottomNavItem(
        label = "Payments",
        route = Routes.PAYMENTS,
        selectedIcon = Icons.Filled.CurrencyRupee,
        unselectedIcon = Icons.Outlined.CurrencyRupee
    ),
    BottomNavItem(
        label = "Profile",
        route = Routes.PROFILE,
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person
    )
)

/**
 * Five-tab bottom navigation. Material 3's [NavigationBar] already guarantees a
 * generous minimum height on phones (comfortably > the 64dp spec), supplies the
 * brand-primary indicator pill via [NavigationBarItemDefaults.colors], and
 * handles the filled/outlined icon swap we wire below.
 *
 * Tap → short haptic tick, then navigate. We [popUpTo] the graph's start
 * destination with [saveState]/[restoreState] so switching tabs preserves each
 * section's scroll position and ViewModels, and use [launchSingleTop] to avoid
 * stacking duplicate copies of the same tab destination.
 */
@Composable
fun BottomNavBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val haptics = LocalHapticFeedback.current

    NavigationBar(
        tonalElevation = 6.dp
    ) {
        navItems.forEach { item ->
            val selected = currentRoute == item.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    // Fire a short tick on every tap — including re-taps on the
                    // already-selected tab — so the press never feels silent.
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (!selected) {
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.label,
                        modifier = Modifier.size(32.dp)
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
