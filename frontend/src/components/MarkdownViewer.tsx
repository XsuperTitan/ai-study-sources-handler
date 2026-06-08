import { useState } from 'react'
import { Modal, Spin } from 'antd'
import ReactMarkdown, { defaultUrlTransform } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize'
import { api } from '../api'
import { prepareMarkdown } from '../markdown'
import type { Citation } from '../types'
import MermaidDiagram from './MermaidDiagram'

const sanitizeSchema = {
  ...defaultSchema,
  protocols: {
    ...defaultSchema.protocols,
    href: [...(defaultSchema.protocols?.href ?? []), 'citation'],
  },
}

export default function MarkdownViewer({
  markdown,
  packageId,
}: {
  markdown: string
  packageId: string
}) {
  const [citation, setCitation] = useState<Citation | null>(null)
  const [citationError, setCitationError] = useState('')
  const [loading, setLoading] = useState(false)

  async function openCitation(blockId: string) {
    setLoading(true)
    setCitation(null)
    setCitationError('')
    try {
      setCitation(await api.citation(packageId, blockId))
    } catch (error) {
      setCitationError(error instanceof Error ? error.message : '引用不存在或暂时无法读取。')
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <article className="markdown-paper">
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[[rehypeSanitize, sanitizeSchema]]}
          urlTransform={(url) =>
            url.startsWith('citation://') ? url : defaultUrlTransform(url)
          }
          components={{
            a: ({ href, children }) => {
              if (href?.startsWith('citation://')) {
                const blockId = href.slice('citation://'.length)
                return (
                  <button className="citation-link" onClick={() => openCitation(blockId)}>
                    {children}
                  </button>
                )
              }
              return (
                <a href={href} target="_blank" rel="noopener noreferrer">
                  {children}
                </a>
              )
            },
            code: ({ className, children }) => {
              const language = /language-(\w+)/.exec(className ?? '')?.[1]
              const value = String(children).replace(/\n$/, '')
              return language === 'mermaid' ? (
                <MermaidDiagram chart={value} />
              ) : (
                <code className={className}>{children}</code>
              )
            },
            img: ({ src, alt }) => <img src={src} alt={alt ?? '资料图片'} loading="lazy" />,
          }}
        >
          {prepareMarkdown(markdown, packageId)}
        </ReactMarkdown>
      </article>
      <Modal
        open={loading || Boolean(citation) || Boolean(citationError)}
        title={citation?.displayName ?? (citationError ? '来源不可用' : '读取来源')}
        footer={null}
        onCancel={() => {
          setCitation(null)
          setCitationError('')
        }}
      >
        {loading ? (
          <Spin />
        ) : citationError ? (
          <div className="citation-card">
            <p>{citationError}</p>
          </div>
        ) : (
          citation && (
            <div className="citation-card">
              <p>{citation.excerpt}</p>
              {citation.assetUrl && (
                <a href={citation.assetUrl} target="_blank" rel="noopener noreferrer">
                  打开原始资源
                </a>
              )}
            </div>
          )
        )}
      </Modal>
    </>
  )
}
