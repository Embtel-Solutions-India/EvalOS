import { describe, expect, it } from 'vitest'
import { noteText } from './noteText'

describe('noteText', () => {
  it('turns a GHL AI call note into lines of text', () => {
    const html =
      '<p style="padding-left: 0px!important;">AI CALL - <span data-cv-variable="x" data-cv-token="true">info_only</span></p>' +
      '<p style="padding-left: 0px!important;">Summary:</p><p>Caller asked about the company&rsquo;s rating &amp; pricing.</p>'
    expect(noteText(html)).toBe('AI CALL - info_only\nSummary:\nCaller asked about the company’s rating & pricing.')
  })

  it('keeps the line breaks inside a transcript', () => {
    expect(noteText('<p><span>Agent: Hi\nUser: Hello\n</span></p>')).toBe('Agent: Hi\nUser: Hello')
  })

  it('leaves a plain note exactly as it was', () => {
    expect(noteText('Called back, wants a quote < $500')).toBe('Called back, wants a quote < $500')
  })

  it('drops scripts rather than showing them', () => {
    expect(noteText('<p>Hi</p><script>alert(1)</script>')).toBe('Hi')
  })

  it('decodes numeric entities', () => {
    expect(noteText('<p>It&#39;s 5&#x3A;01 PM</p>')).toBe("It's 5:01 PM")
  })
})
