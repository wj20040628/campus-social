import { chromium } from 'file:///F:/%E6%A0%A1%E5%9B%AD%E7%A4%BE%E4%BA%A4/.e2e-env/node_modules/playwright/index.mjs'
const b = await chromium.launch({channel:'chrome'})
const ctx = await b.newContext({viewport:{width:390,height:844},deviceScaleFactor:2,isMobile:true,hasTouch:true})
const p = await ctx.newPage()
await p.goto('http://127.0.0.1:5173/login/form',{waitUntil:'domcontentloaded'})
await p.waitForTimeout(2200)
const r = await p.evaluate(()=>{
  const inp=document.querySelector('.el-input__inner')
  const lbl=document.querySelector('.el-form-item__label')
  const item=document.querySelector('.el-form-item')
  const g=(e,p)=>e?getComputedStyle(e)[p]:null
  return {
    inputFontSize: g(inp,'fontSize'),
    inputMinHeight: g(document.querySelector('.el-input__wrapper'),'minHeight'),
    labelFontSize: g(lbl,'fontSize'),
    labelWeight: g(lbl,'fontWeight'),
    itemMarginBottom: g(item,'marginBottom'),
    dotBefore: lbl ? getComputedStyle(lbl,'::before').content : null,
    dotBg: lbl ? getComputedStyle(lbl,'::before').backgroundColor : null,
  }
})
console.log(JSON.stringify(r,null,2))
await b.close()
