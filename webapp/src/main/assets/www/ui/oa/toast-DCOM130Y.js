import{A as e,C as t,M as n,N as r,R as i,V as a,_ as o,a as s,b as c,c as l,h as u,j as d,v as f,y as p}from"./solid-Csl37FmM.js";import{_ as m,a as h,c as g,f as _,g as v,m as y,n as ee,o as b,u as x,v as S}from"./web-C8KihOh2.js";import{r as C}from"./LP6E37CW-C_22jSRP.js";import{i as w}from"./i18n-DWDfPEG0.js";import{n as T,r as E,t as te}from"./store-B6igxVu_.js";/* empty css               */import{n as D}from"./reduced-nsQJmDHw.js";var O=m(`<div><div class=sonner-spinner>`),k=m(`<div class=sonner-loading-bar>`),A=m(`<svg xmlns=http://www.w3.org/2000/svg viewBox="0 0 20 20"fill=currentColor height=20 width=20><path fill-rule=evenodd d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.857-9.809a.75.75 0 00-1.214-.882l-3.483 4.79-1.88-1.88a.75.75 0 10-1.06 1.061l2.5 2.5a.75.75 0 001.137-.089l4-5.5z"clip-rule=evenodd>`),j=m(`<svg xmlns=http://www.w3.org/2000/svg viewBox="0 0 24 24"fill=currentColor height=20 width=20><path fill-rule=evenodd d="M9.401 3.003c1.155-2 4.043-2 5.197 0l7.355 12.748c1.154 2-.29 4.5-2.599 4.5H4.645c-2.309 0-3.752-2.5-2.598-4.5L9.4 3.003zM12 8.25a.75.75 0 01.75.75v3.75a.75.75 0 01-1.5 0V9a.75.75 0 01.75-.75zm0 8.25a.75.75 0 100-1.5.75.75 0 000 1.5z"clip-rule=evenodd>`),M=m(`<svg xmlns=http://www.w3.org/2000/svg viewBox="0 0 20 20"fill=currentColor height=20 width=20><path fill-rule=evenodd d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a.75.75 0 000 1.5h.253a.25.25 0 01.244.304l-.459 2.066A1.75 1.75 0 0010.747 15H11a.75.75 0 000-1.5h-.253a.25.25 0 01-.244-.304l.459-2.066A1.75 1.75 0 009.253 9H9z"clip-rule=evenodd>`),N=m(`<svg xmlns=http://www.w3.org/2000/svg viewBox="0 0 20 20"fill=currentColor height=20 width=20><path fill-rule=evenodd d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-8-5a.75.75 0 01.75.75v4.5a.75.75 0 01-1.5 0v-4.5A.75.75 0 0110 5zm0 10a1 1 0 100-2 1 1 0 000 2z"clip-rule=evenodd>`),P=m(`<svg xmlns=http://www.w3.org/2000/svg width=12 height=12 viewBox="0 0 24 24"fill=none stroke=currentColor stroke-width=1.5 stroke-linecap=round stroke-linejoin=round><line x1=18 y1=6 x2=6 y2=18></line><line x1=6 y1=6 x2=18 y2=18>`),F=m(`<div>`),I=m(`<button data-close-button>`),ne=m(`<div data-icon>`),L=m(`<div data-description>`),re=m(`<li tabindex=0 data-sonner-toast><div data-content><div data-title>`),ie=m(`<button data-button data-cancel>`),ae=m(`<button data-button data-action>`),oe=m(`<section tabindex=-1 aria-live=polite aria-relevant="additions text"aria-atomic=false data-react-aria-top-layer>`),R=m(`<ol tabindex=-1 data-sonner-toaster style=--width:356px>`);function z(e,{insertAt:t}={}){if(!e||typeof document>`u`)return;let n=document.head||document.getElementsByTagName(`head`)[0],r=document.createElement(`style`);r.type=`text/css`,t===`top`&&n.firstChild?n.insertBefore(r,n.firstChild):n.appendChild(r),r.styleSheet?r.styleSheet.cssText=e:r.appendChild(document.createTextNode(e))}z(`html[dir=ltr],
[data-sonner-toaster][dir=ltr] {
  --toast-icon-margin-start: -3px;
  --toast-icon-margin-end: 4px;
  --toast-svg-margin-start: -1px;
  --toast-svg-margin-end: 0px;
  --toast-button-margin-start: auto;
  --toast-button-margin-end: 0;
  --toast-close-button-start: 0;
  --toast-close-button-end: unset;
  --toast-close-button-transform: translate(-35%, -35%);
}
html[dir=rtl],
[data-sonner-toaster][dir=rtl] {
  --toast-icon-margin-start: 4px;
  --toast-icon-margin-end: -3px;
  --toast-svg-margin-start: 0px;
  --toast-svg-margin-end: -1px;
  --toast-button-margin-start: 0;
  --toast-button-margin-end: auto;
  --toast-close-button-start: unset;
  --toast-close-button-end: 0;
  --toast-close-button-transform: translate(35%, -35%);
}
[data-sonner-toaster] {
  position: fixed;
  width: var(--width);
  font-family:
    ui-sans-serif,
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    Segoe UI,
    Roboto,
    Helvetica Neue,
    Arial,
    Noto Sans,
    sans-serif,
    Apple Color Emoji,
    Segoe UI Emoji,
    Segoe UI Symbol,
    Noto Color Emoji;
  --gray1: hsl(0, 0%, 99%);
  --gray2: hsl(0, 0%, 97.3%);
  --gray3: hsl(0, 0%, 95.1%);
  --gray4: hsl(0, 0%, 93%);
  --gray5: hsl(0, 0%, 90.9%);
  --gray6: hsl(0, 0%, 88.7%);
  --gray7: hsl(0, 0%, 85.8%);
  --gray8: hsl(0, 0%, 78%);
  --gray9: hsl(0, 0%, 56.1%);
  --gray10: hsl(0, 0%, 52.3%);
  --gray11: hsl(0, 0%, 43.5%);
  --gray12: hsl(0, 0%, 9%);
  --border-radius: 8px;
  box-sizing: border-box;
  padding: 0;
  margin: 0;
  list-style: none;
  outline: none;
  z-index: 999999999;
  transition: transform 400ms ease;
}
@media (hover: none) and (pointer: coarse) {
  [data-sonner-toaster][data-lifted=true] {
    transform: none;
  }
}
[data-sonner-toaster][data-x-position=right] {
  right: var(--offset-right);
}
[data-sonner-toaster][data-x-position=left] {
  left: var(--offset-left);
}
[data-sonner-toaster][data-x-position=center] {
  left: 50%;
  transform: translateX(-50%);
}
[data-sonner-toaster][data-y-position=top] {
  top: var(--offset-top);
}
[data-sonner-toaster][data-y-position=bottom] {
  bottom: var(--offset-bottom);
}
[data-sonner-toast] {
  --y: translateY(100%);
  --lift-amount: calc(var(--lift) * var(--gap));
  z-index: var(--z-index);
  position: absolute;
  opacity: 0;
  transform: var(--y);
  touch-action: none;
  transition:
    transform 400ms,
    opacity 400ms,
    height 400ms,
    box-shadow 200ms;
  box-sizing: border-box;
  outline: none;
  overflow-wrap: anywhere;
}
[data-sonner-toast][data-styled=true] {
  padding: 16px;
  background: var(--normal-bg);
  border: 1px solid var(--normal-border);
  color: var(--normal-text);
  border-radius: var(--border-radius);
  box-shadow: 0px 4px 12px rgba(0, 0, 0, 0.1);
  width: var(--width);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
}
[data-sonner-toast]:focus-visible {
  box-shadow: 0px 4px 12px rgba(0, 0, 0, 0.1), 0 0 0 2px rgba(0, 0, 0, 0.2);
}
[data-sonner-toast][data-y-position=top] {
  top: 0;
  --y: translateY(-100%);
  --lift: 1;
  --lift-amount: calc(1 * var(--gap));
}
[data-sonner-toast][data-y-position=bottom] {
  bottom: 0;
  --y: translateY(100%);
  --lift: -1;
  --lift-amount: calc(var(--lift) * var(--gap));
}
[data-sonner-toast][data-styled=true] [data-description] {
  font-weight: 400;
  line-height: 1.4;
  color: #3f3f3f;
}
[data-rich-colors=true][data-sonner-toast][data-styled=true] [data-description] {
  color: inherit;
}
[data-sonner-toaster][data-sonner-theme=dark] [data-description] {
  color: hsl(0, 0%, 91%);
}
[data-sonner-toast][data-styled=true] [data-title] {
  font-weight: 500;
  line-height: 1.5;
  color: inherit;
}
[data-sonner-toast][data-styled=true] [data-icon] {
  display: flex;
  height: 16px;
  width: 16px;
  position: relative;
  justify-content: flex-start;
  align-items: center;
  flex-shrink: 0;
  margin-left: var(--toast-icon-margin-start);
  margin-right: var(--toast-icon-margin-end);
}
[data-sonner-toast][data-promise=true] [data-icon] > svg {
  opacity: 0;
  transform: scale(0.8);
  transform-origin: center;
  animation: sonner-fade-in 300ms ease forwards;
}
[data-sonner-toast][data-styled=true] [data-icon] > * {
  flex-shrink: 0;
}
[data-sonner-toast][data-styled=true] [data-icon] svg {
  margin-left: var(--toast-svg-margin-start);
  margin-right: var(--toast-svg-margin-end);
}
[data-sonner-toast][data-styled=true] [data-content] {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
[data-sonner-toast][data-styled=true] [data-button] {
  border-radius: 4px;
  padding-left: 8px;
  padding-right: 8px;
  height: 24px;
  font-size: 12px;
  color: var(--normal-bg);
  background: var(--normal-text);
  margin-left: var(--toast-button-margin-start);
  margin-right: var(--toast-button-margin-end);
  border: none;
  font-weight: 500;
  cursor: pointer;
  outline: none;
  display: flex;
  align-items: center;
  flex-shrink: 0;
  transition: opacity 400ms, box-shadow 200ms;
}
[data-sonner-toast][data-styled=true] [data-button]:focus-visible {
  box-shadow: 0 0 0 2px rgba(0, 0, 0, 0.4);
}
[data-sonner-toast][data-styled=true] [data-button]:first-of-type {
  margin-left: var(--toast-button-margin-start);
  margin-right: var(--toast-button-margin-end);
}
[data-sonner-toast][data-styled=true] [data-cancel] {
  color: var(--normal-text);
  background: rgba(0, 0, 0, 0.08);
}
[data-sonner-toaster][data-sonner-theme=dark] [data-sonner-toast][data-styled=true] [data-cancel] {
  background: rgba(255, 255, 255, 0.3);
}
[data-sonner-toast][data-styled=true] [data-close-button] {
  position: absolute;
  left: var(--toast-close-button-start);
  right: var(--toast-close-button-end);
  top: 0;
  height: 20px;
  width: 20px;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 0;
  color: var(--normal-text);
  background: var(--normal-bg);
  border: 1px solid var(--normal-border);
  transform: var(--toast-close-button-transform);
  border-radius: 50%;
  cursor: pointer;
  z-index: 1;
  transition:
    opacity 100ms,
    background 200ms,
    border-color 200ms;
}
[data-sonner-toast][data-styled=true] [data-close-button]:focus-visible {
  box-shadow: 0px 4px 12px rgba(0, 0, 0, 0.1), 0 0 0 2px rgba(0, 0, 0, 0.2);
}
[data-sonner-toast][data-styled=true] [data-disabled=true] {
  cursor: not-allowed;
}
[data-sonner-toast][data-styled=true]:hover [data-close-button]:hover {
  background: var(--gray2);
  border-color: var(--gray5);
}
[data-sonner-toast][data-swiping=true]::before {
  content: "";
  position: absolute;
  left: -100%;
  right: -100%;
  height: 100%;
  z-index: -1;
}
[data-sonner-toast][data-y-position=top][data-swiping=true]::before {
  bottom: 50%;
  transform: scaleY(3) translateY(50%);
}
[data-sonner-toast][data-y-position=bottom][data-swiping=true]::before {
  top: 50%;
  transform: scaleY(3) translateY(-50%);
}
[data-sonner-toast][data-swiping=false][data-removed=true]::before {
  content: "";
  position: absolute;
  inset: 0;
  transform: scaleY(2);
}
[data-sonner-toast][data-expanded=true]::after {
  content: "";
  position: absolute;
  left: 0;
  height: calc(var(--gap) + 1px);
  bottom: 100%;
  width: 100%;
}
[data-sonner-toast][data-mounted=true] {
  --y: translateY(0);
  opacity: 1;
}
[data-sonner-toast][data-expanded=false][data-front=false] {
  --scale: var(--toasts-before) * 0.05 + 1;
  --y: translateY(calc(var(--lift-amount) * var(--toasts-before))) scale(calc(-1 * var(--scale)));
  height: var(--front-toast-height);
}
[data-sonner-toast] > * {
  transition: opacity 400ms;
}
[data-sonner-toast][data-x-position=right] {
  right: 0;
}
[data-sonner-toast][data-x-position=left] {
  left: 0;
}
[data-sonner-toast][data-expanded=false][data-front=false][data-styled=true] > * {
  opacity: 0;
}
[data-sonner-toast][data-visible=false] {
  opacity: 0;
  pointer-events: none;
}
[data-sonner-toast][data-mounted=true][data-expanded=true] {
  --y: translateY(calc(var(--lift) * var(--offset)));
  height: var(--initial-height);
}
[data-sonner-toast][data-removed=true][data-front=true][data-swipe-out=false] {
  --y: translateY(calc(var(--lift) * -100%));
  opacity: 0;
}
[data-sonner-toast][data-removed=true][data-front=false][data-swipe-out=false][data-expanded=true] {
  --y: translateY(calc(var(--lift) * var(--offset) + var(--lift) * -100%));
  opacity: 0;
}
[data-sonner-toast][data-removed=true][data-front=false][data-swipe-out=false][data-expanded=false] {
  --y: translateY(40%);
  opacity: 0;
  transition: transform 500ms, opacity 200ms;
}
[data-sonner-toast][data-removed=true][data-front=false]::before {
  height: calc(var(--initial-height) + 20%);
}
[data-sonner-toast][data-swiping=true] {
  transform: var(--y) translateY(var(--swipe-amount-y, 0px)) translateX(var(--swipe-amount-x, 0px));
  transition: none;
}
[data-sonner-toast][data-swiped=true] {
  -webkit-user-select: none;
  user-select: none;
}
[data-sonner-toast][data-swipe-out=true][data-y-position=bottom],
[data-sonner-toast][data-swipe-out=true][data-y-position=top] {
  animation-duration: 200ms;
  animation-timing-function: ease-out;
  animation-fill-mode: forwards;
}
[data-sonner-toast][data-swipe-out=true][data-swipe-direction=left] {
  animation-name: swipe-out-left;
}
[data-sonner-toast][data-swipe-out=true][data-swipe-direction=right] {
  animation-name: swipe-out-right;
}
[data-sonner-toast][data-swipe-out=true][data-swipe-direction=up] {
  animation-name: swipe-out-up;
}
[data-sonner-toast][data-swipe-out=true][data-swipe-direction=down] {
  animation-name: swipe-out-down;
}
@keyframes swipe-out-left {
  from {
    transform: var(--y) translateX(var(--swipe-amount-x));
    opacity: 1;
  }
  to {
    transform: var(--y) translateX(calc(var(--swipe-amount-x) - 100%));
    opacity: 0;
  }
}
@keyframes swipe-out-right {
  from {
    transform: var(--y) translateX(var(--swipe-amount-x));
    opacity: 1;
  }
  to {
    transform: var(--y) translateX(calc(var(--swipe-amount-x) + 100%));
    opacity: 0;
  }
}
@keyframes swipe-out-up {
  from {
    transform: var(--y) translateY(var(--swipe-amount-y));
    opacity: 1;
  }
  to {
    transform: var(--y) translateY(calc(var(--swipe-amount-y) - 100%));
    opacity: 0;
  }
}
@keyframes swipe-out-down {
  from {
    transform: var(--y) translateY(var(--swipe-amount-y));
    opacity: 1;
  }
  to {
    transform: var(--y) translateY(calc(var(--swipe-amount-y) + 100%));
    opacity: 0;
  }
}
@media (max-width: 600px) {
  [data-sonner-toaster] {
    position: fixed;
    right: var(--mobile-offset-right);
    left: var(--mobile-offset-left);
    width: 100%;
  }
  [data-sonner-toaster][dir=rtl] {
    left: calc(var(--mobile-offset-left) * -1);
  }
  [data-sonner-toaster] [data-sonner-toast] {
    left: 0;
    right: 0;
    width: calc(100% - var(--mobile-offset-left) * 2);
  }
  [data-sonner-toaster][data-x-position=left] {
    left: var(--mobile-offset-left);
  }
  [data-sonner-toaster][data-y-position=bottom] {
    bottom: var(--mobile-offset-bottom);
  }
  [data-sonner-toaster][data-y-position=top] {
    top: var(--mobile-offset-top);
  }
  [data-sonner-toaster][data-x-position=center] {
    left: var(--mobile-offset-left);
    right: var(--mobile-offset-right);
    transform: none;
  }
}
[data-sonner-toaster][data-sonner-theme=light] {
  --normal-bg: #fff;
  --normal-border: var(--gray4);
  --normal-text: var(--gray12);
  --success-bg: hsl(143, 85%, 96%);
  --success-border: hsl(145, 92%, 87%);
  --success-text: hsl(140, 100%, 27%);
  --info-bg: hsl(208, 100%, 97%);
  --info-border: hsl(221, 91%, 93%);
  --info-text: hsl(210, 92%, 45%);
  --warning-bg: hsl(49, 100%, 97%);
  --warning-border: hsl(49, 91%, 84%);
  --warning-text: hsl(31, 92%, 45%);
  --error-bg: hsl(359, 100%, 97%);
  --error-border: hsl(359, 100%, 94%);
  --error-text: hsl(360, 100%, 45%);
}
[data-sonner-toaster][data-sonner-theme=light] [data-sonner-toast][data-invert=true] {
  --normal-bg: #000;
  --normal-border: hsl(0, 0%, 20%);
  --normal-text: var(--gray1);
}
[data-sonner-toaster][data-sonner-theme=dark] [data-sonner-toast][data-invert=true] {
  --normal-bg: #fff;
  --normal-border: var(--gray3);
  --normal-text: var(--gray12);
}
[data-sonner-toaster][data-sonner-theme=dark] {
  --normal-bg: #000;
  --normal-bg-hover: hsl(0, 0%, 12%);
  --normal-border: hsl(0, 0%, 20%);
  --normal-border-hover: hsl(0, 0%, 25%);
  --normal-text: var(--gray1);
  --success-bg: hsl(150, 100%, 6%);
  --success-border: hsl(147, 100%, 12%);
  --success-text: hsl(150, 86%, 65%);
  --info-bg: hsl(215, 100%, 6%);
  --info-border: hsl(223, 43%, 17%);
  --info-text: hsl(216, 87%, 65%);
  --warning-bg: hsl(64, 100%, 6%);
  --warning-border: hsl(60, 100%, 9%);
  --warning-text: hsl(46, 87%, 65%);
  --error-bg: hsl(358, 76%, 10%);
  --error-border: hsl(357, 89%, 16%);
  --error-text: hsl(358, 100%, 81%);
}
[data-sonner-toaster][data-sonner-theme=dark] [data-sonner-toast] [data-close-button] {
  background: var(--normal-bg);
  border-color: var(--normal-border);
  color: var(--normal-text);
}
[data-sonner-toaster][data-sonner-theme=dark] [data-sonner-toast] [data-close-button]:hover {
  background: var(--normal-bg-hover);
  border-color: var(--normal-border-hover);
}
[data-rich-colors=true][data-sonner-toast][data-type=success] {
  background: var(--success-bg);
  border-color: var(--success-border);
  color: var(--success-text);
}
[data-rich-colors=true][data-sonner-toast][data-type=success] [data-close-button] {
  background: var(--success-bg);
  border-color: var(--success-border);
  color: var(--success-text);
}
[data-rich-colors=true][data-sonner-toast][data-type=info] {
  background: var(--info-bg);
  border-color: var(--info-border);
  color: var(--info-text);
}
[data-rich-colors=true][data-sonner-toast][data-type=info] [data-close-button] {
  background: var(--info-bg);
  border-color: var(--info-border);
  color: var(--info-text);
}
[data-rich-colors=true][data-sonner-toast][data-type=warning] {
  background: var(--warning-bg);
  border-color: var(--warning-border);
  color: var(--warning-text);
}
[data-rich-colors=true][data-sonner-toast][data-type=warning] [data-close-button] {
  background: var(--warning-bg);
  border-color: var(--warning-border);
  color: var(--warning-text);
}
[data-rich-colors=true][data-sonner-toast][data-type=error] {
  background: var(--error-bg);
  border-color: var(--error-border);
  color: var(--error-text);
}
[data-rich-colors=true][data-sonner-toast][data-type=error] [data-close-button] {
  background: var(--error-bg);
  border-color: var(--error-border);
  color: var(--error-text);
}
.sonner-loading-wrapper {
  --size: 16px;
  height: var(--size);
  width: var(--size);
  position: absolute;
  inset: 0;
  z-index: 10;
}
.sonner-loading-wrapper[data-visible=false] {
  transform-origin: center;
  animation: sonner-fade-out 0.2s ease forwards;
}
.sonner-spinner {
  position: relative;
  top: 50%;
  left: 50%;
  height: var(--size);
  width: var(--size);
}
.sonner-loading-bar {
  animation: sonner-spin 1.2s linear infinite;
  background: var(--gray11);
  border-radius: 6px;
  height: 8%;
  left: -10%;
  position: absolute;
  top: -3.9%;
  width: 24%;
}
.sonner-loading-bar:nth-child(1) {
  animation-delay: -1.2s;
  transform: rotate(0.0001deg) translate(146%);
}
.sonner-loading-bar:nth-child(2) {
  animation-delay: -1.1s;
  transform: rotate(30deg) translate(146%);
}
.sonner-loading-bar:nth-child(3) {
  animation-delay: -1s;
  transform: rotate(60deg) translate(146%);
}
.sonner-loading-bar:nth-child(4) {
  animation-delay: -0.9s;
  transform: rotate(90deg) translate(146%);
}
.sonner-loading-bar:nth-child(5) {
  animation-delay: -0.8s;
  transform: rotate(120deg) translate(146%);
}
.sonner-loading-bar:nth-child(6) {
  animation-delay: -0.7s;
  transform: rotate(150deg) translate(146%);
}
.sonner-loading-bar:nth-child(7) {
  animation-delay: -0.6s;
  transform: rotate(180deg) translate(146%);
}
.sonner-loading-bar:nth-child(8) {
  animation-delay: -0.5s;
  transform: rotate(210deg) translate(146%);
}
.sonner-loading-bar:nth-child(9) {
  animation-delay: -0.4s;
  transform: rotate(240deg) translate(146%);
}
.sonner-loading-bar:nth-child(10) {
  animation-delay: -0.3s;
  transform: rotate(270deg) translate(146%);
}
.sonner-loading-bar:nth-child(11) {
  animation-delay: -0.2s;
  transform: rotate(300deg) translate(146%);
}
.sonner-loading-bar:nth-child(12) {
  animation-delay: -0.1s;
  transform: rotate(330deg) translate(146%);
}
@keyframes sonner-fade-in {
  0% {
    opacity: 0;
    transform: scale(0.8);
  }
  100% {
    opacity: 1;
    transform: scale(1);
  }
}
@keyframes sonner-fade-out {
  0% {
    opacity: 1;
    transform: scale(1);
  }
  100% {
    opacity: 0;
    transform: scale(0.8);
  }
}
@keyframes sonner-spin {
  0% {
    opacity: 1;
  }
  100% {
    opacity: 0.15;
  }
}
@media (prefers-reduced-motion) {
  [data-sonner-toast],
  [data-sonner-toast] > *,
  .sonner-loading-bar {
    transition: none !important;
    animation: none !important;
  }
}
.sonner-loader {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  transform-origin: center;
  transition: opacity 200ms, transform 200ms;
}
.sonner-loader[data-visible=false] {
  opacity: 0;
  transform: scale(0.8) translate(-50%, -50%);
}
`);var B=Array(12).fill(0);function se(e){return(()=>{var t=O(),n=t.firstChild;return g(n,u(s,{each:B,children:(e,t)=>(()=>{var e=k();return c(()=>_(e,`data-index`,t())),e})()})),c(n=>{var r=[`sonner-loading-wrapper`,e.class??e.className].filter(Boolean).join(` `),i=e.visible;return r!==n.e&&h(t,n.e=r),i!==n.t&&_(t,`data-visible`,n.t=i),n},{e:void 0,t:void 0}),t})()}function ce(){return A()}function le(){return j()}function V(){return M()}function H(){return N()}function ue(e){switch(e){case`success`:return u(ce,{});case`info`:return u(V,{});case`warning`:return u(le,{});case`error`:return u(H,{});default:return null}}function de(){return P()}function fe(){let[e,i]=t(typeof document<`u`&&document.hidden);return r(()=>{let e=()=>{i(document.hidden)};document.addEventListener(`visibilitychange`,e),n(()=>{document.removeEventListener(`visibilitychange`,e)})}),e}var U=1,pe=class{subscribers;toasts;dismissedToasts;constructor(){this.subscribers=[],this.toasts=[],this.dismissedToasts=new Set}subscribe=e=>(this.subscribers.push(e),()=>{let t=this.subscribers.indexOf(e);this.subscribers.splice(t,1)});publish=e=>{this.subscribers.forEach(t=>t(e))};addToast=e=>{this.publish(e),this.toasts=[...this.toasts,e]};create=e=>{let{message:t,...n}=e,r=typeof e.id==`string`&&e.id.length>0,i=typeof e.id==`number`||r?e.id:U++,a=this.toasts.find(e=>e.id===i),o=e.dismissible===void 0||e.dismissible;return this.dismissedToasts.has(i)&&this.dismissedToasts.delete(i),a?this.toasts=this.toasts.map(n=>n.id===i?(this.publish({...n,...e,id:i,title:t,dismissible:o}),{...n,...e,id:i,dismissible:o,title:t}):n):this.addToast({title:t,...n,dismissible:o,id:i}),i};dismiss=e=>(e?(this.dismissedToasts.add(e),requestAnimationFrame(()=>{this.subscribers.forEach(t=>t({id:e,dismiss:!0}))})):this.toasts.forEach(e=>{this.dismissedToasts.add(e.id),this.subscribers.forEach(t=>t({id:e.id,dismiss:!0}))}),e);message=(e,t)=>this.create({...t,message:e});error=(e,t)=>this.create({...t,message:e,type:`error`});success=(e,t)=>this.create({...t,type:`success`,message:e});info=(e,t)=>this.create({...t,type:`info`,message:e});warning=(e,t)=>this.create({...t,type:`warning`,message:e});loading=(e,t)=>this.create({...t,type:`loading`,message:e});promise=(e,t)=>{if(!t)return;let n;t.loading!==void 0&&(n=this.create({...t,promise:e,type:`loading`,message:t.loading,description:typeof t.description==`function`?void 0:t.description}));let r=Promise.resolve(typeof e==`function`?e():e),i=n!==void 0,a,o=r.then(async e=>{if(a=[`resolve`,e],he(e))i=!1,this.create({id:n,type:`default`,message:e});else if(me(e)&&!e.ok){i=!1;let r=typeof t.error==`function`?await t.error(`HTTP error! status: ${e.status}`):t.error,a=typeof t.description==`function`?await t.description(`HTTP error! status: ${e.status}`):t.description,o=W(r)?r:{message:r};this.create({id:n,type:`error`,description:a,...o})}else if(e instanceof Error){i=!1;let r=typeof t.error==`function`?await t.error(e):t.error,a=typeof t.description==`function`?await t.description(e):t.description,o=W(r)?r:{message:r};this.create({id:n,type:`error`,description:a,...o})}else if(t.success!==void 0){i=!1;let r=typeof t.success==`function`?await t.success(e):t.success,a=typeof t.description==`function`?await t.description(e):t.description,o=W(r)?r:{message:r};this.create({id:n,type:`success`,description:a,...o})}}).catch(async e=>{if(a=[`reject`,e],t.error!==void 0){i=!1;let r=typeof t.error==`function`?await t.error(e):t.error,a=typeof t.description==`function`?await t.description(e):t.description,o=W(r)?r:{message:r};this.create({id:n,type:`error`,description:a,...o})}}).finally(()=>{i&&(this.dismiss(n),n=void 0),t.finally?.()}),s=()=>new Promise((e,t)=>{o.then(()=>{a[0]===`reject`?t(a[1]):e(a[1])}).catch(t)});return typeof n!=`string`&&typeof n!=`number`?{unwrap:s}:Object.assign(n,{unwrap:s})};custom=(e,t)=>{let n=t?.id||U++;return this.create({jsx:e(n),...t,id:n}),n};getActiveToasts=()=>this.toasts.filter(e=>!this.dismissedToasts.has(e.id))};function me(e){return e&&typeof e==`object`&&`ok`in e&&typeof e.ok==`boolean`&&`status`in e&&typeof e.status==`number`}function W(e){return typeof e==`object`&&!!e&&`message`in e}function he(e){return typeof Node<`u`&&e instanceof Node}var G=new pe;function ge(e,t){let n=t?.id||U++;return G.addToast({title:e,...t,id:n}),n}var K=Object.assign(ge,{success:G.success,info:G.info,warning:G.warning,error:G.error,custom:G.custom,message:G.message,promise:G.promise,dismiss:G.dismiss,loading:G.loading},{getHistory:()=>G.toasts,getToasts:()=>G.getActiveToasts()});function _e(e){return typeof e==`object`&&!!e&&`label`in e}var ve=3,q=`24px`,J=`16px`,ye=4e3,be=14,xe=45,Se=200;function Y(...e){return e.filter(Boolean).join(` `)}function Ce(e){return typeof e==`function`?e():e}function we(e){let[t,n]=e.split(`-`),r=[];return t&&r.push(t),n&&r.push(n),r}function Te(){if(typeof window>`u`||typeof document>`u`)return`ltr`;let e=document.documentElement.getAttribute(`dir`);return e===`auto`||!e?window.getComputedStyle(document.documentElement).direction:e}function Ee(e,t){let n={};return[e,t].forEach((e,t)=>{let r=t===1,i=r?`--mobile-offset`:`--offset`,a=r?J:q,o=e=>{for(let t of[`top`,`right`,`bottom`,`left`])n[`${i}-${t}`]=typeof e==`number`?`${e}px`:e};if(typeof e==`number`||typeof e==`string`){o(e);return}if(typeof e==`object`&&e){for(let t of[`top`,`right`,`bottom`,`left`]){let r=e[t];n[`${i}-${t}`]=r===void 0?a:typeof r==`number`?`${r}px`:r}return}o(a)}),n}function De(e,t){return e??t}function Oe(e,t){return e??t??``}function ke(e,t){return e??t??``}function Ae(e){return e===`system`?typeof window<`u`&&window.matchMedia(`(prefers-color-scheme: dark)`).matches?`dark`:`light`:e}function je(e){let[i,a]=t(!1),[o,s]=t(!1),[m,y]=t(!1),[ee,b]=t(!1),[C,w]=t(!1),[T,E]=t(null),[te,D]=t(null),[O,k]=t(0),[A,j]=t(0),M,N=null,P=0,oe=0,R=ye,[z,B]=t(null),ce=()=>e.index===0,le=()=>e.index+1<=e.visibleToasts,V=()=>e.toast.type,H=()=>e.toast.dismissible!==!1,U=()=>De(e.classNames,e.classes),pe=()=>ke(e.className,e.class),me=()=>Oe(e.descriptionClassName,e.descriptionClass),W=()=>ke(e.toast.className,e.toast.class),he=()=>Oe(e.toast.descriptionClassName,e.toast.descriptionClass),G=()=>De(e.toast.classNames,e.toast.classes),ge=()=>e.toast.closeButton??e.closeButton,K=()=>e.toast.duration??e.duration??ye,ve=()=>e.toast.invert??e.invert,q=()=>V()===`loading`,J=()=>e.swipeDirections??we(e.position),Te=p(()=>e.position.split(`-`)[0]),Ee=p(()=>e.position.split(`-`)[1]),Ae=p(()=>Math.max(0,e.heights.findIndex(t=>t.toastId===e.toast.id))),je=p(()=>e.heights.reduce((e,t,n)=>n>=Ae()?e:e+t.height,0)),X=p(()=>Ae()*(e.gap??be)+je()),Me=fe();f(()=>{R=K()});function Z(){s(!0),k(X()),e.setHeights(t=>t.filter(t=>t.toastId!==e.toast.id)),window.setTimeout(()=>{e.removeToast(e.toast)},Se)}function Ne(){return e.icons?.loading?(()=>{var t=F();return g(t,()=>e.icons.loading),c(e=>{var n=Y(U()?.loader,G()?.loader,`sonner-loader`),r=V()===`loading`;return n!==e.e&&h(t,e.e=n),r!==e.t&&_(t,`data-visible`,e.t=r),e},{e:void 0,t:void 0}),t})():u(se,{get class(){return Y(U()?.loader,G()?.loader)},get visible(){return V()===`loading`}})}let Pe=()=>e.toast.icon??e.icons?.[V()]??ue(V());return r(()=>{if(a(!0),!M)return;let t=M.getBoundingClientRect().height;j(t),e.setHeights(n=>[{toastId:e.toast.id,toasterId:e.toast.toasterId,height:t,position:e.toast.position},...n]),n(()=>{e.setHeights(t=>t.filter(t=>t.toastId!==e.toast.id))})}),f(()=>{if(!i()||!M)return;Ce(e.toast.title),Ce(e.toast.description);let t=M.style.height;M.style.height=`auto`;let n=M.getBoundingClientRect().height;M.style.height=t,j(n),e.setHeights(t=>t.find(t=>t.toastId===e.toast.id)?t.map(t=>t.toastId===e.toast.id?{...t,height:n}:t):[{toastId:e.toast.id,toasterId:e.toast.toasterId,height:n,position:e.toast.position},...t])}),f(d(()=>[e.expanded,e.interacting,e.toast,V(),Me(),K()],([t,r,i,a])=>{if(i.promise&&a===`loading`||i.duration===1/0||a===`loading`)return;let o;t||r||(e.pauseWhenPageIsHidden??!0)&&Me()?(()=>{if(oe<P){let e=new Date().getTime()-P;R-=e}oe=new Date().getTime()})():R!==1/0&&(P=new Date().getTime(),o=setTimeout(()=>{i.onAutoClose?.(i),Z()},R)),n(()=>{o&&clearTimeout(o)})})),f(d(()=>e.toast.delete,t=>{t&&(Z(),e.toast.onDismiss?.(e.toast))})),(()=>{var t=re(),n=t.firstChild,r=n.firstChild;t.$$pointermove=e=>{if(!z()||!H()||(window.getSelection()?.toString().length??0)>0)return;let t=e.clientY-z().y,n=e.clientX-z().x;!T()&&(Math.abs(n)>1||Math.abs(t)>1)&&E(Math.abs(n)>Math.abs(t)?`x`:`y`);let r={x:0,y:0},i=e=>1/(1.5+Math.abs(e)/20);T()===`y`?(J().includes(`top`)||J().includes(`bottom`))&&(r.y=J().includes(`top`)&&t<0||J().includes(`bottom`)&&t>0?t:t*i(t)):T()===`x`&&(J().includes(`left`)||J().includes(`right`))&&(r.x=J().includes(`left`)&&n<0||J().includes(`right`)&&n>0?n:n*i(n)),(Math.abs(r.x)>0||Math.abs(r.y)>0)&&w(!0),M?.style.setProperty(`--swipe-amount-x`,`${r.x}px`),M?.style.setProperty(`--swipe-amount-y`,`${r.y}px`)},t.$$pointerup=()=>{if(ee()||!H())return;B(null);let t=Number(M?.style.getPropertyValue(`--swipe-amount-x`).replace(`px`,``)||0),n=Number(M?.style.getPropertyValue(`--swipe-amount-y`).replace(`px`,``)||0),r=Math.max(1,new Date().getTime()-(N?.getTime()??0)),i=T()===`x`?t:n,a=Math.abs(i)/r;if(Math.abs(i)>=xe||a>.11){k(X()),e.toast.onDismiss?.(e.toast),T()===`x`?D(t>0?`right`:`left`):D(n>0?`down`:`up`),Z(),b(!0);return}M?.style.setProperty(`--swipe-amount-x`,`0px`),M?.style.setProperty(`--swipe-amount-y`,`0px`),w(!1),y(!1),E(null)},t.$$pointerdown=e=>{e.button!==2&&(q()||!H()||e.target.closest(`button`)||(N=new Date,k(X()),e.currentTarget.setPointerCapture(e.pointerId),y(!0),B({x:e.clientX,y:e.clientY})))},t.addEventListener(`dragend`,()=>{y(!1),E(null),B(null)});var a=M;return typeof a==`function`?S(a,t):M=t,g(t,u(l,{get when(){return x(()=>!!(ge()&&!e.toast.jsx))()&&V()!==`loading`},get children(){var t=I();return t.$$click=()=>{q()||!H()||(Z(),e.toast.onDismiss?.(e.toast))},t.$$pointerup=e=>e.stopPropagation(),t.$$pointerdown=e=>e.stopPropagation(),g(t,()=>e.icons?.close??u(de,{})),c(n=>{var r=e.closeButtonAriaLabel??`Close toast`,i=q(),a=Y(U()?.closeButton,G()?.closeButton);return r!==n.e&&_(t,`aria-label`,n.e=r),i!==n.t&&_(t,`data-disabled`,n.t=i),a!==n.a&&h(t,n.a=a),n},{e:void 0,t:void 0,a:void 0}),t}}),n),g(t,u(l,{get when(){return x(()=>!!((V()||e.toast.icon||e.toast.promise)&&e.toast.icon!==null))()&&(e.icons?.[V()]!==null||e.toast.icon)},get children(){var t=ne();return g(t,(()=>{var t=x(()=>!!(e.toast.promise||e.toast.type===`loading`&&!e.toast.icon));return()=>t()?e.toast.icon||Ne():null})(),null),g(t,(()=>{var t=x(()=>e.toast.type!==`loading`);return()=>t()?Pe():null})(),null),c(()=>h(t,Y(U()?.icon,G()?.icon))),t}}),n),g(r,(()=>{var t=x(()=>!!e.toast.jsx);return()=>t()?e.toast.jsx:Ce(e.toast.title)})()),g(n,u(l,{get when(){return e.toast.description},get children(){var t=L();return g(t,()=>Ce(e.toast.description)),c(()=>h(t,Y(me(),he(),U()?.description,G()?.description))),t}}),null),g(t,u(l,{get when(){return e.toast.cancel},children:t=>u(l,{get when(){return!_e(t())},get fallback(){return(()=>{var n=ie();return n.$$click=e=>{let n=t();H()&&(n.onClick?.(e),Z())},g(n,()=>t().label),c(t=>{var r=e.toast.cancelButtonStyle??e.cancelButtonStyle,i=Y(U()?.cancelButton,G()?.cancelButton);return t.e=v(n,r,t.e),i!==t.t&&h(n,t.t=i),t},{e:void 0,t:void 0}),n})()},get children(){return t()}})}),null),g(t,u(l,{get when(){return e.toast.action},children:t=>u(l,{get when(){return!_e(t())},get fallback(){return(()=>{var n=ae();return n.$$click=e=>{t().onClick?.(e),!e.defaultPrevented&&Z()},g(n,()=>t().label),c(t=>{var r=e.toast.actionButtonStyle??e.actionButtonStyle,i=Y(U()?.actionButton,G()?.actionButton);return t.e=v(n,r,t.e),i!==t.t&&h(n,t.t=i),t},{e:void 0,t:void 0}),n})()},get children(){return t()}})}),null),c(a=>{var s=Y(pe(),W(),U()?.toast,G()?.toast,U()?.default,U()?.[V()],G()?.[V()]),c=e.toast.richColors??e.defaultRichColors,l=!(e.toast.jsx||e.toast.unstyled||e.unstyled),u=i(),d=!!e.toast.promise,f=C(),p=o(),g=le(),y=Te(),b=Ee(),x=e.index,S=ce(),w=m(),T=H(),E=V(),D=ve(),k=ee(),j=te(),M=!!(e.expanded||e.expandByDefault&&i()),N=e.toast.testId,P={"--index":e.index,"--toasts-before":e.index,"--z-index":e.toasts.length-e.index,"--offset":`${o()?O():X()}px`,"--initial-height":e.expandByDefault?`auto`:`${A()}px`,...e.style,...e.toast.style},F=Y(U()?.content,G()?.content),I=Y(U()?.title,G()?.title);return s!==a.e&&h(t,a.e=s),c!==a.t&&_(t,`data-rich-colors`,a.t=c),l!==a.a&&_(t,`data-styled`,a.a=l),u!==a.o&&_(t,`data-mounted`,a.o=u),d!==a.i&&_(t,`data-promise`,a.i=d),f!==a.n&&_(t,`data-swiped`,a.n=f),p!==a.s&&_(t,`data-removed`,a.s=p),g!==a.h&&_(t,`data-visible`,a.h=g),y!==a.r&&_(t,`data-y-position`,a.r=y),b!==a.d&&_(t,`data-x-position`,a.d=b),x!==a.l&&_(t,`data-index`,a.l=x),S!==a.u&&_(t,`data-front`,a.u=S),w!==a.c&&_(t,`data-swiping`,a.c=w),T!==a.w&&_(t,`data-dismissible`,a.w=T),E!==a.m&&_(t,`data-type`,a.m=E),D!==a.f&&_(t,`data-invert`,a.f=D),k!==a.y&&_(t,`data-swipe-out`,a.y=k),j!==a.g&&_(t,`data-swipe-direction`,a.g=j),M!==a.p&&_(t,`data-expanded`,a.p=M),N!==a.b&&_(t,`data-testid`,a.b=N),a.T=v(t,P,a.T),F!==a.A&&h(n,a.A=F),I!==a.O&&h(r,a.O=I),a},{e:void 0,t:void 0,a:void 0,o:void 0,i:void 0,n:void 0,s:void 0,h:void 0,r:void 0,d:void 0,l:void 0,u:void 0,c:void 0,w:void 0,m:void 0,f:void 0,y:void 0,g:void 0,p:void 0,b:void 0,T:void 0,A:void 0,O:void 0}),t})()}function X(i){let a=e({position:`bottom-right`,hotkey:[`altKey`,`KeyT`],theme:`light`,gap:be,visibleToasts:ve,dir:Te(),containerAriaLabel:`Notifications`},i),o=Ae(a.theme),[d,m]=te({toasts:[]}),y=p(()=>{let e=d.toasts;return a.id?e.filter(e=>e.toasterId===a.id):e.filter(e=>!e.toasterId)}),ee=p(()=>Array.from(new Set([a.position,...y().filter(e=>e.position).map(e=>e.position)]))),[b,x]=t([]),[C,w]=t(!1),[D,O]=t(!1),[k,A]=t(o),[j,M]=t(null),[N,P]=t(!1),F,I=()=>a.hotkey.join(`+`).replace(/Key/g,``).replace(/Digit/g,``),ne=()=>ke(a.className,a.class),L=()=>a.toastOptions,re=()=>De(L()?.classNames,L()?.classes),ie=()=>Oe(L()?.descriptionClassName,L()?.descriptionClass),ae=()=>ke(L()?.className,L()?.class),z=e=>{m(`toasts`,t=>(t.find(t=>t.id===e.id)?.delete||G.dismiss(e.id),t.filter(({id:t})=>t!==e.id)))};return r(()=>{let e=G.subscribe(e=>{if(e.dismiss){requestAnimationFrame(()=>{m(`toasts`,T(t=>{t.forEach(t=>{t.id===e.id&&(t.delete=!0)})}))});return}let t=e,n=d.toasts.findIndex(e=>e.id===t.id);if(n!==-1){m(`toasts`,n,E(t));return}m(`toasts`,T(e=>{e.unshift(t)}))});n(e)}),f(()=>{let e=a.theme;if(e!==`system`){A(e);return}if(typeof window>`u`)return;let t=window.matchMedia(`(prefers-color-scheme: dark)`),r=({matches:e})=>{A(e?`dark`:`light`)};r(t);try{t.addEventListener(`change`,r),n(()=>t.removeEventListener(`change`,r))}catch{t.addListener(r),n(()=>t.removeListener(r))}}),f(()=>{y().length<=1&&w(!1)}),r(()=>{let e=e=>{a.hotkey.length>0&&a.hotkey.every(t=>e[t]||e.code===t)&&(w(!0),F?.focus()),e.code===`Escape`&&(document.activeElement===F||F?.contains(document.activeElement))&&w(!1)};document.addEventListener(`keydown`,e),n(()=>document.removeEventListener(`keydown`,e))}),n(()=>{j()&&(j()?.focus({preventScroll:!0}),M(null),P(!1))}),(()=>{var e=oe();return g(e,u(s,{get each(){return ee()},children:(e,t)=>{let[n,r]=e.split(`-`),i=p(()=>y().filter(n=>!n.position&&t()===0||n.position===e)),o=p(()=>b().filter(n=>t()===0&&!n.position||n.position===e));return u(l,{get when(){return y().length>0},get children(){var t=R();t.$$pointerup=()=>O(!1),t.$$pointerdown=e=>{e.target instanceof HTMLElement&&e.target.dataset.dismissible===`false`||O(!0)},t.addEventListener(`dragend`,()=>w(!1)),t.addEventListener(`mouseleave`,()=>{D()||w(!1)}),t.$$mousemove=()=>w(!0),t.addEventListener(`mouseenter`,()=>w(!0)),t.addEventListener(`focus`,e=>{e.target instanceof HTMLElement&&e.target.dataset.dismissible===`false`||N()||(P(!0),M(e.relatedTarget))}),t.addEventListener(`blur`,e=>{N()&&!e.currentTarget.contains(e.relatedTarget)&&(P(!1),j()&&(j()?.focus({preventScroll:!0}),M(null)))});var l=F;return typeof l==`function`?S(l,t):F=t,_(t,`data-y-position`,n),_(t,`data-x-position`,r),g(t,u(s,{get each(){return i()},children:(t,n)=>u(je,{get icons(){return a.icons},get index(){return n()},toast:t,get defaultRichColors(){return a.richColors},get duration(){return L()?.duration??a.duration},get class(){return ae()},get descriptionClassName(){return ie()},get invert(){return!!a.invert},get visibleToasts(){return a.visibleToasts},get closeButton(){return L()?.closeButton??a.closeButton??!1},get interacting(){return D()},position:e,get style(){return L()?.style},get unstyled(){return L()?.unstyled},get classNames(){return re()},get cancelButtonStyle(){return L()?.cancelButtonStyle},get actionButtonStyle(){return L()?.actionButtonStyle},get closeButtonAriaLabel(){return L()?.closeButtonAriaLabel},removeToast:z,get toasts(){return i()},get heights(){return o()},setHeights:x,get expandByDefault(){return!!a.expand},get gap(){return a.gap},get expanded(){return C()},get swipeDirections(){return a.swipeDirections},get pauseWhenPageIsHidden(){return a.pauseWhenPageIsHidden}})})),c(e=>{var n=a.dir===`auto`?Te():a.dir,r=ne(),i=k(),s={"--front-toast-height":`${o()[0]?.height??0}px`,"--gap":`${a.gap}px`,...a.style,...Ee(a.offset,a.mobileOffset)};return n!==e.e&&_(t,`dir`,e.e=n),r!==e.t&&h(t,e.t=r),i!==e.a&&_(t,`data-sonner-theme`,e.a=i),e.o=v(t,s,e.o),e},{e:void 0,t:void 0,a:void 0,o:void 0}),t}})}})),c(()=>_(e,`aria-label`,a.customAriaLabel??`${a.containerAriaLabel} ${I()}`)),e})()}b([`pointerdown`,`pointerup`,`pointermove`,`click`,`mousemove`]);var Me=2e3;function Z(e){return!e.description||e.description.length<=2e3?e:{...e,description:`${e.description.slice(0,Me)}…`}}var Ne=m(`<div data-slot=toast-v2-icon>`),Pe=m(`<div data-slot=toast-v2-content>`),Fe=m(`<div data-slot=toast-v2-title>`),Ie=m(`<div data-slot=toast-v2-description>`),Le=m(`<div data-slot=toast-v2-actions>`),Re=m(`<button type=button data-slot=toast-v2-close-button>`),ze=m(`<svg width=16 height=16 viewBox="0 0 16 16"fill=none xmlns=http://www.w3.org/2000/svg aria-hidden=true><path d="M4.25 11.75L11.75 4.25"stroke=currentColor></path><path d="M11.75 11.75L4.25 4.25"stroke=currentColor>`),Be=m(`<button type=button data-component=button-v2 data-size=small>`);function Ve(t){let a=w(),[o,s]=i(t,[`class`,`className`,`style`,`toastOptions`,`swipeDirections`]);return r(()=>{let e=()=>{document.querySelectorAll(`.toast-v2-region .toast-v2`).forEach(e=>{let t=e.dataset.visible===`false`;e.inert=t,e.tabIndex=t?-1:0})},t=!1,r=()=>{let n=document.querySelectorAll(`.toast-v2-region`);n.length&&(i.disconnect(),n.forEach(e=>{i.observe(e,{subtree:!0,childList:!0,attributes:!0,attributeFilter:[`data-visible`]})}),t=!0,e())},i=new MutationObserver(()=>{t?e():r()});i.observe(document.body,{subtree:!0,childList:!0}),queueMicrotask(r),n(()=>i.disconnect())}),u(ee,{get children(){return u(X,e({get position(){return C(a.locale())?`bottom-left`:`bottom-right`},get offset(){return C(a.locale())?{left:32,bottom:48}:{right:32,bottom:48}},mobileOffset:16,gap:12,duration:5e3,swipeDirections:[`bottom`],get className(){return[`toast-v2-region`,o.className,o.class].filter(Boolean).join(` `)},get style(){return{"--width":`320px`,...o.style}},get toastOptions(){return{...o.toastOptions,unstyled:!0,closeButton:!0,closeButtonAriaLabel:o.toastOptions?.closeButtonAriaLabel??a.t(`ui.common.dismiss`)}}},s))}})}var He=o();function Ue(e){return u(He.Provider,{get value(){return e.toastId},get children(){return e.children}})}function We(e){return(()=>{var t=Ne();return y(t,e,!1,!1),t})()}function Ge(e){return(()=>{var t=Pe();return y(t,e,!1,!1),t})()}function Ke(e){return(()=>{var t=Fe();return y(t,e,!1,!1),t})()}function qe(e){return(()=>{var t=Ie();return y(t,e,!1,!1),t})()}function Je(e){return(()=>{var t=Le();return y(t,e,!1,!1),t})()}function Ye(t){let n=w(),r=a(He),[o,s]=i(t,[`children`,`onClick`]);return(()=>{var t=Re();return y(t,e({get"aria-label"(){return n.t(`ui.common.dismiss`)}},s,{onClick:e=>{o.onClick instanceof Function&&o.onClick(e),!e.defaultPrevented&&r!==void 0&&$e.dismiss(r)}}),!1,!0),g(t,()=>o.children??u(Xe,{})),t})()}function Xe(){return ze()}var Ze=Object.assign(Ue,{Region:Ve,Icon:We,Content:Ge,Title:Ke,Description:qe,Actions:Je,CloseButton:Ye}),Qe=0,$e={show(e,t){let n=--Qe;return K.custom(t=>e({toastId:Number(t)}),{id:n,className:`toast-v2`,duration:t?.persistent?1/0:t?.duration,unstyled:!0,description:t?.resize?()=>{t.resize?.()}:void 0}),n},dismiss(e){return e===void 0?(Q.clear(),$.clear()):it($.get(e)),K.dismiss(e)}},Q=new Map,$=new Map;function et(e){let t=Z(typeof e==`string`?{description:e}:e),n=JSON.stringify({title:t.title,description:t.description,variant:t.variant,duration:t.duration,persistent:t.persistent,actions:t.actions?.map(e=>[e.label,e.variant])}),r=Q.get(n),i=K.getToasts();if(r&&i.at(-1)?.id===r.id)return r.options=t,tt(r),rt(r.id),r.id;r&&i.some(e=>e.id===r.id)&&$e.dismiss(r.id),it(r);let a={id:--Qe,key:n,options:t};return a.actions=nt(a),Q.set(n,a),$.set(a.id,a),tt(a),a.id}function tt(e){let t=()=>it(e);K(e.options.title??``,{id:e.id,description:e.options.description,icon:e.options.icon,action:e.actions,closeButton:!0,duration:e.options.persistent?1/0:e.options.duration,className:`toast-v2 toast-v2--${e.options.variant??`default`}`,testId:`toast-v2-${e.id}`,unstyled:!0,onDismiss:t,onAutoClose:t})}function nt(e){if(e.options.actions?.length)return u(Ze.Actions,{get children(){return e.options.actions.map((t,n)=>(()=>{var r=Be();return r.$$click=()=>{let t=e.options.actions?.[n]?.onClick;t&&t!==`dismiss`&&t(),$e.dismiss(e.id)},g(r,()=>t.label),c(e=>{var n=t.variant===`secondary`?`ghost`:`neutral`,i=t.variant??`primary`;return n!==e.e&&_(r,`data-variant`,e.e=n),i!==e.t&&_(r,`data-action-variant`,e.t=i),e},{e:void 0,t:void 0}),r})())}})}function rt(e){typeof document>`u`||typeof requestAnimationFrame>`u`||requestAnimationFrame(()=>{let t=document.querySelector(`[data-testid="toast-v2-${e}"]`);!t||D()||t.animate([{scale:1},{scale:1.025},{scale:1}],{duration:160,easing:`ease-out`})})}function it(e){e&&(Q.get(e.key)===e&&Q.delete(e.key),$.get(e.id)===e&&$.delete(e.id))}b([`click`]);export{et as n,$e as r,Ze as t};