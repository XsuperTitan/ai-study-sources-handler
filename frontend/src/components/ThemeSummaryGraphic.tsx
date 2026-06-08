import type { CSSProperties } from 'react'
import type { StudyGuide } from '../types'

const MAX_SEQUENCE = 5
const MAX_CORE = 6
const MAX_CARD_ITEMS = 3

function take(values: string[] = [], limit: number) {
  return values.filter(Boolean).slice(0, limit)
}

function SummaryCard({
  title,
  values,
}: {
  title: string
  values: string[]
}) {
  const visible = take(values, MAX_CARD_ITEMS)
  if (!visible.length) return null
  return (
    <section>
      <h3>{title}</h3>
      <ul>
        {visible.map((value, index) => (
          <li key={`${title}-${index}-${value}`}>{value}</li>
        ))}
      </ul>
    </section>
  )
}

export default function ThemeSummaryGraphic({
  title,
  guide,
  illustrationUrl,
}: {
  title: string
  guide: StudyGuide
  illustrationUrl?: string
}) {
  const style = illustrationUrl
    ? ({ '--theme-summary-bg': `url("${illustrationUrl}")` } as CSSProperties)
    : undefined
  const sequence = take(guide.recommendedSequence, MAX_SEQUENCE)
  const corePoints = take(
    guide.coreKnowledgePoints.length ? guide.coreKnowledgePoints : guide.keyPoints,
    MAX_CORE,
  )
  const focusItems = guide.keyPoints.length ? guide.keyPoints : guide.learningObjectives

  return (
    <section className="theme-summary-graphic" aria-label="AI 一图总结" style={style}>
      <div className="theme-summary-backdrop" aria-hidden="true" />
      <div className="theme-summary-content">
        <header className="theme-summary-header">
          <span className="eyebrow">AI THEME / STUDY SNAPSHOT</span>
          <h2>{title}</h2>
          <div className="theme-summary-meta">
            <span>{guide.difficulty || '未标注难度'}</span>
            <span>约 {guide.estimatedMinutes || 30} 分钟</span>
          </div>
        </header>

        <p className="theme-summary-overview">{guide.overview}</p>

        <div className="theme-summary-body">
          {sequence.length > 0 && (
            <section className="theme-summary-path">
              <h3>推荐路径</h3>
              <ol>
                {sequence.map((value, index) => (
                  <li key={`${index}-${value}`}>
                    <b>{String(index + 1).padStart(2, '0')}</b>
                    <span>{value}</span>
                  </li>
                ))}
              </ol>
            </section>
          )}

          {corePoints.length > 0 && (
            <section className="theme-summary-core">
              <h3>核心知识点</h3>
              <div>
                {corePoints.map((value, index) => (
                  <span key={`${index}-${value}`}>{value}</span>
                ))}
              </div>
            </section>
          )}

          <div className="theme-summary-cards">
            <SummaryCard title="重点" values={focusItems} />
            <SummaryCard title="难点" values={guide.difficultPoints} />
            <SummaryCard title="常见错误" values={guide.commonMistakes} />
            <SummaryCard title="练习" values={guide.exercises} />
          </div>
        </div>
      </div>
    </section>
  )
}
