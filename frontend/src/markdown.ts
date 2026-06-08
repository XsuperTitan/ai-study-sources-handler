export function prepareMarkdown(markdown: string, packageId: string) {
  return markdown
    .replace(/\[\[cite:(blk_[a-f0-9]{32})]]/gi, '[来源](citation://$1)')
    .replace(/\s+\bblk_[a-f0-9]{32}\b/gi, '')
    .replace(/asset:\/\/([a-f0-9-]+)/gi, `/api/v1/packages/${packageId}/assets/$1`)
}
