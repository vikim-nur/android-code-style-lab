package fixture.ui

import androidx.compose.runtime.Composable

@Composable fun CallbackScreen(onBack: () -> Unit) {}
@Composable fun AppNavHost(controller: androidx.navigation.NavController) {}
fun PlainScreen(controller: androidx.navigation.NavController) {}
