export function prepareMarkdown(markdown: string, packageId: string) {
  return markdown
    .replace(/\s*\[\[cite:blk_[a-f0-9]{32}]]/gi, '')
    .replace(/\s+\bblk_[a-f0-9]{32}\b/gi, '')
    .replace(/asset:\/\/([a-f0-9-]+)/gi, `/api/v1/packages/${packageId}/assets/$1`)
}
