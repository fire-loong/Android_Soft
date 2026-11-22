package com.actionloop.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.actionloop.android.data.ApiClient
import com.actionloop.android.data.ApiException
import com.actionloop.android.data.Session
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var isRegister by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    fun submit() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val session = if (isRegister) {
                    ApiClient.register(email.trim(), username.trim(), password, displayName.trim().ifBlank { null })
                } else {
                    ApiClient.login(login.trim(), password)
                }
                Session.save(session)
                onLoggedIn()
            } catch (e: ApiException) {
                snackbar.showSnackbar("${e.code}: ${e.message}")
            } catch (e: Exception) {
                snackbar.showSnackbar("网络错误：${e.message}")
            } finally {
                loading = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("ActionLoop", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text("把通知、截图、文件变成可核验完成的统一事项", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))

        if (isRegister) {
            OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(username, { username = it }, label = { Text("用户名（3-32）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(displayName, { displayName = it }, label = { Text("昵称（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(password, { password = it }, label = { Text("密码（≥8）") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(login, { login = it }, label = { Text("账号（用户名/邮箱）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { submit() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            if (loading) CircularProgressIndicator(modifier = Modifier.height(20.dp)) else Text(if (isRegister) "注册并登录" else "登录")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { isRegister = !isRegister }) {
            Text(if (isRegister) "已有账号？去登录" else "没有账号？去注册")
        }
        SnackbarHost(snackbar)
    }
}
