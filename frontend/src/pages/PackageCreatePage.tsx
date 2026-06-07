import { InboxOutlined } from '@ant-design/icons'
import { useMutation } from '@tanstack/react-query'
import { Alert, Button, Form, Input, Select, Switch, Upload, message } from 'antd'
import type { UploadFile } from 'antd'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import { api } from '../api'

const allowed = ['.pdf', '.txt', '.md', '.png', '.jpg', '.jpeg', '.webp']

export default function PackageCreatePage() {
  const navigate = useNavigate()
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [form] = Form.useForm()
  const totalBytes = useMemo(
    () => fileList.reduce((sum, item) => sum + (item.size ?? 0), 0),
    [fileList],
  )
  const create = useMutation({
    mutationFn: api.createPackage,
    onSuccess: ({ packageId }) => navigate(`/packages/${packageId}`),
    onError: (error: Error) => message.error(error.message),
  })

  function submit(values: Record<string, string | boolean>) {
    const text = String(values.textContent ?? '')
    if (!fileList.length && !text.trim()) {
      message.warning('请添加文件或粘贴文本。')
      return
    }
    const data = new FormData()
    fileList.forEach((item) => item.originFileObj && data.append('files', item.originFileObj))
    if (text.trim()) data.append('textContent', text)
    if (values.title) data.append('title', String(values.title))
    data.append('outputLanguage', String(values.outputLanguage ?? 'ZH_CN'))
    data.append('noteStyle', String(values.noteStyle ?? 'INTERVIEW'))
    data.append('generateIllustration', String(values.generateIllustration ?? true))
    create.mutate(data)
  }

  return (
    <div className="page form-page">
      <div className="form-intro">
        <span className="eyebrow">NEW SOURCE PACKAGE / 02</span>
        <h1>组合你的学习资料</h1>
        <p>PDF、文本和截图会按照提交顺序合并为一篇笔记。每个结论尽可能保留来源位置。</p>
      </div>
      <div className="form-layout">
        <Form
          form={form}
          layout="vertical"
          initialValues={{ outputLanguage: 'ZH_CN', noteStyle: 'INTERVIEW', generateIllustration: true }}
          onFinish={submit}
          className="source-form"
        >
          <Form.Item label="资料标题" name="title">
            <Input size="large" placeholder="例如：Java 并发与线程池复习资料" maxLength={120} />
          </Form.Item>
          <Form.Item label="文件">
            <Upload.Dragger
              multiple
              fileList={fileList}
              beforeUpload={() => false}
              accept={allowed.join(',')}
              onChange={({ fileList: next }) => setFileList(next.slice(0, 20))}
            >
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">拖入 PDF、文本或截图</p>
              <p className="ant-upload-hint">最多 20 个文件，总计不超过 100 MB</p>
            </Upload.Dragger>
            <div className="upload-meter">
              <span>{fileList.length}/20 个文件</span>
              <span>{(totalBytes / 1024 / 1024).toFixed(1)} / 100 MB</span>
            </div>
          </Form.Item>
          <Form.Item label="粘贴文本" name="textContent">
            <Input.TextArea
              rows={8}
              placeholder="也可以直接粘贴技术文章、面试题或课堂记录……"
              showCount
            />
          </Form.Item>
          <div className="form-row">
            <Form.Item label="输出语言" name="outputLanguage">
              <Select options={[{ value: 'ZH_CN', label: '简体中文' }, { value: 'EN', label: 'English' }]} />
            </Form.Item>
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
            type="warning"
            showIcon
            title="图片会发送给千问 VL，文本会发送给 DeepSeek。请勿上传密钥、密码或无权处理的资料。"
          />
          <Button
            type="primary"
            htmlType="submit"
            size="large"
            block
            loading={create.isPending}
            className="submit-button"
          >
            开始处理
          </Button>
        </Form>
        <aside className="process-note">
          <span>PROCESS MAP</span>
          {['接收并保存原始资料', '解析文本与页面', '识别截图和扫描页', '生成可引用摘要', '编排 Markdown 笔记', '生成学习指南'].map(
            (item, index) => (
              <div key={item}>
                <b>{String(index + 1).padStart(2, '0')}</b>
                <p>{item}</p>
              </div>
            ),
          )}
        </aside>
      </div>
    </div>
  )
}
