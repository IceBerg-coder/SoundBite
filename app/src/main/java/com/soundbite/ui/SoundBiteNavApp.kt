package com.soundbite.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.soundbite.ui.record.RecordScreen
import com.soundbite.ui.record.RecordViewModel
import com.soundbite.ui.search.SearchAudioViewModel
import com.soundbite.ui.search.SearchScreen

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    data object Record : Screen("record", "Record", Icons.Default.Mic)
    data object Search : Screen("search", "Semantic Search", Icons.Default.Search)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundBiteNavApp(
    recordViewModel: RecordViewModel,
    searchViewModel: SearchAudioViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val items = listOf(Screen.Record, Screen.Search)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (currentRoute == Screen.Search.route) "Semantic Audio Search" else "SoundBite",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                items.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Record.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Record.route) {
                RecordScreen(
                    viewModel = recordViewModel,
                    onNavigateToSearch = {
                        navController.navigate(Screen.Search.route)
                    }
                )
            }
            composable(Screen.Search.route) {
                SearchScreen(
                    viewModel = searchViewModel,
                    onNavigateToRecord = {
                        navController.navigate(Screen.Record.route)
                    }
                )
            }
        }
    }
}

