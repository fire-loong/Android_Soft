import { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Alert, Button, Card, Form, Input, Segmented, Space, Typography, message } from 'antd'
import { useNavigate } from 'react-router-dom'
import { getErrorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { isAuthenticated } from '../lib/authTokens'

interface LoginForm {
  login: string
  password: string
}
interface RegisterForm {
  email: string
  username: string
  password: string
  displayName?: string
}

const DEVICE_NAME = 'ActionLoop Web'
const DEVICE_PLATFORM = 'WEB' as const

export default function Login() {
  const navigate = useNavigate()
  const { login, register } = useAuth()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [loginForm] = Form.useForm<LoginForm>()
  const [registerForm] = Form.useForm<RegisterForm>()

  const loginMut = useMutation({
    mutationFn: async (values: LoginForm) => {
      await login({ ...values, deviceName: DEVICE_NAME, devicePlatform: DEVICE_PLATFORM })
    },
    onSuccess: () => {
      message.success('登录成功')
      navigate('/today', { replace: true })
    },
    onError: (err) => message.error(getErrorMessage(err, '登录失败'))
  })

  const registerMut = useMutation({
    mutationFn: async (values: RegisterForm) => {
      await register({ ...values, deviceName: DEVICE_NAME, devicePlatform: DEVICE_PLATFORM })
    },
    onSuccess: () => {
      message.success('注册成功，已为你开通个人空间')
      navigate('/today', { replace: true })
    },
    onError: (err) => message.error(getErrorMessage(err, '注册失败'))
  })

  useEffect(() => {
    if (isAuthenticated()) navigate('/today', { replace: true })
  }, [navigate])

  const submitting = loginMut.isPending || registerMut.isPending

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f2f5' }}>
      <Card style={{ width: 400 }}>
        <Typography.Title level={3} style={{ textAlign: 'center' }}>
          ActionLoop
        </Typography.Title>
        <Typography.Paragraph type="secondary" style={{ textAlign: 'center' }}>
          把通知、截图、文件变成可核验完成的统一事项
        </Typography.Paragraph>

        <Space style={{ width: '100%', justifyContent: 'center', marginBottom: 16 }}>
          <Segmented
            value={mode}
            onChange={(v) => setMode(v as 'login' | 'register')}
            options={[
              { label: '登录', value: 'login' },
              { label: '注册', value: 'register' }
            ]}
          />
        </Space>

        {mode === 'login' ? (
          <Form
            form={loginForm}
            layout="vertical"
            onFinish={(v) => loginMut.mutate(v)}
            disabled={submitting}
          >
            <Form.Item label="账号" name="login" rules={[{ required: true, message: '请输入用户名或邮箱' }]}>
              <Input placeholder="用户名 / 邮箱" autoComplete="username" />
            </Form.Item>
            <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password placeholder="密码" autoComplete="current-password" />
            </Form.Item>
            <Button type="primary" htmlType="submit" block loading={loginMut.isPending}>
              登录
            </Button>
          </Form>
        ) : (
          <Form
            form={registerForm}
            layout="vertical"
            onFinish={(v) => registerMut.mutate(v)}
            disabled={submitting}
          >
            <Form.Item label="邮箱" name="email" rules={[{ required: true, type: 'email', message: '请输入有效邮箱' }]}>
              <Input placeholder="email@example.com" autoComplete="email" />
            </Form.Item>
            <Form.Item
              label="用户名"
              name="username"
              rules={[
                { required: true, message: '请输入用户名' },
                { min: 3, max: 32, message: '3-32 个字符' }
              ]}
            >
              <Input placeholder="3-32 个字符" autoComplete="username" />
            </Form.Item>
            <Form.Item label="昵称" name="displayName">
              <Input placeholder="选填" />
            </Form.Item>
            <Form.Item
              label="密码"
              name="password"
              rules={[
                { required: true, message: '请输入密码' },
                { min: 8, max: 128, message: '至少 8 个字符' }
              ]}
            >
              <Input.Password placeholder="至少 8 个字符" autoComplete="new-password" />
            </Form.Item>
            <Button type="primary" htmlType="submit" block loading={registerMut.isPending}>
              注册并登录
            </Button>
          </Form>
        )}

        <Alert
          style={{ marginTop: 16 }}
          type="info"
          showIcon
          message="后端运行于 8080，前端通过 Vite 代理同源访问"
        />
      </Card>
    </div>
  )
}
