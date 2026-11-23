import '@testing-library/jest-dom'

// jsdom 未实现带伪元素的 getComputedStyle；antd Modal 测量滚动条时会传入 pseudoElt，
// 忽略第二个参数避免测试输出被 Not implemented 噪声淹没。
const originalGetComputedStyle = window.getComputedStyle.bind(window)
window.getComputedStyle = ((element: Element) =>
  originalGetComputedStyle(element)) as typeof window.getComputedStyle
