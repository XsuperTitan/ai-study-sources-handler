import { Alert, Spin } from 'antd'
import { useEffect, useId, useState } from 'react'

export default function MermaidDiagram({ chart }: { chart: string }) {
  const id = useId().replace(/:/g, '')
  const [result, setResult] = useState({ chart: '', svg: '', failed: false })

  useEffect(() => {
    let active = true
    import('mermaid')
      .then(({ default: mermaid }) => {
        mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral' })
        return mermaid.render(`mermaid-${id}`, chart)
      })
      .then((rendered) => active && setResult({ chart, svg: rendered.svg, failed: false }))
      .catch(() => active && setResult({ chart, svg: '', failed: true }))
    return () => {
      active = false
    }
  }, [chart, id])

  if (result.chart !== chart) return <Spin size="small" />
  if (result.failed) {
    return (
      <div className="mermaid-fallback">
        <Alert type="warning" showIcon message="流程图暂时无法渲染，已显示原始 Mermaid。" />
        <pre className="code-block">{chart}</pre>
      </div>
    )
  }
  return <div className="mermaid-block" dangerouslySetInnerHTML={{ __html: result.svg }} />
}
