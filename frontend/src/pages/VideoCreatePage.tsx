import { useMutation } from '@tanstack/react-query'
import { Alert, Button, Form, Input, Select, Switch, message } from 'antd'
import { useNavigate } from 'react-router'
import { api } from '../api'

export default function VideoCreatePage() {
  const navigate = useNavigate()
  const create = useMutation({
    mutationFn: api.createVideo,
    onSuccess: ({ packageId }) => navigate(`/packages/${packageId}`),
    onError: (error: Error) => message.error(error.message),
  })

  return (
    <div className="page form-page compact-form">
      <div className="form-intro">
        <span className="eyebrow">VIDEO TRANSCRIPT / 03</span>
        <h1>从字幕提炼视频笔记</h1>
        <p>当前只处理 Bilibili 已有字幕，不下载视频或音频。引用会定位到对应时间段。</p>
      </div>
      <Form
        layout="vertical"
        initialValues={{ noteStyle: 'INTERVIEW', generateIllustration: true }}
        onFinish={(values) => create.mutate({ ...values, outputLanguage: 'ZH_CN' })}
        className="source-form video-form"
      >
        <Form.Item
          label="Bilibili 链接"
          name="url"
          rules={[
            { required: true, message: '请输入视频链接。' },
            { type: 'url', message: '链接格式不正确。' },
          ]}
        >
          <Input size="large" placeholder="https://www.bilibili.com/video/BV..." />
        </Form.Item>
        <Form.Item label="自定义标题" name="title">
          <Input size="large" placeholder="留空时根据字幕内容自动命名" />
        </Form.Item>
        <div className="form-row">
          <Form.Item label="笔记风格" name="noteStyle">
            <Select
              options={[
                { value: 'INTERVIEW', label: '面试速记' },
                { value: 'TEXTBOOK', label: '完整教材' },
              ]}
            />
          </Form.Item>
          <Form.Item label="AI 主题插图" name="generateIllustration" valuePropName="checked">
            <Switch />
          </Form.Item>
        </div>
        <Alert
          type="info"
          showIcon
          title="无字幕、私密或平台限制的视频会明确失败；系统不会绕过访问权限。"
        />
        <Button type="primary" htmlType="submit" size="large" block loading={create.isPending}>
          获取字幕并处理
        </Button>
      </Form>
    </div>
  )
}
