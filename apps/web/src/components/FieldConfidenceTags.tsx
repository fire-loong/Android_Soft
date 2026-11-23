import { Space, Tag, Tooltip, Typography } from 'antd'
import { fieldConfidenceEntries, formatConfidence } from '../lib/aiProvenance'
import type { Candidate } from '../lib/types'

/**
 * 逐字段置信度（指导书 4.3）：低置信字段单独标黄，不用一个总分掩盖日期错误。
 * 未识别字段显示“未识别”，不伪装成已确认结果。
 */
export default function FieldConfidenceTags({ candidate }: { candidate: Candidate }) {
  const entries = fieldConfidenceEntries(candidate)
  const provided = entries.some((e) => e.confidence != null)

  return (
    <Space direction="vertical" size={4} style={{ width: '100%' }}>
      <Space wrap size={4}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          逐字段置信
        </Typography.Text>
        {entries.map((e) => {
          if (e.missing) {
            return (
              <Tag key={e.field} color="default">
                {e.label} 未识别
              </Tag>
            )
          }
          if (e.low) {
            return (
              <Tooltip key={e.field} title="低于提醒阈值：请对照原文逐项核对后再确认">
                <Tag color="gold">
                  {e.label} {formatConfidence(e.confidence)} · 待核对
                </Tag>
              </Tooltip>
            )
          }
          if (e.unknownConfidence) {
            return (
              <Tooltip key={e.field} title="抽取器未给出该字段置信度，请对照原文核对">
                <Tag color="orange">{e.label} 置信未提供</Tag>
              </Tooltip>
            )
          }
          return (
            <Tag key={e.field} color="green">
              {e.label} {formatConfidence(e.confidence)}
            </Tag>
          )
        })}
      </Space>
      {!provided && (
        <Typography.Text type="warning" style={{ fontSize: 12 }}>
          该候选未提供逐字段置信度，请逐项核对原文，不要直接当作确定结果。
        </Typography.Text>
      )}
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        置信度由抽取器给出，用于提示核对重点，不是精确概率。
      </Typography.Text>
    </Space>
  )
}
