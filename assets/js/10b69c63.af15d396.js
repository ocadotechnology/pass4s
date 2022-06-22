"use strict";(self.webpackChunkpass4s=self.webpackChunkpass4s||[]).push([[74],{3905:(e,n,t)=>{t.d(n,{Zo:()=>l,kt:()=>m});var r=t(7294);function o(e,n,t){return n in e?Object.defineProperty(e,n,{value:t,enumerable:!0,configurable:!0,writable:!0}):e[n]=t,e}function a(e,n){var t=Object.keys(e);if(Object.getOwnPropertySymbols){var r=Object.getOwnPropertySymbols(e);n&&(r=r.filter((function(n){return Object.getOwnPropertyDescriptor(e,n).enumerable}))),t.push.apply(t,r)}return t}function s(e){for(var n=1;n<arguments.length;n++){var t=null!=arguments[n]?arguments[n]:{};n%2?a(Object(t),!0).forEach((function(n){o(e,n,t[n])})):Object.getOwnPropertyDescriptors?Object.defineProperties(e,Object.getOwnPropertyDescriptors(t)):a(Object(t)).forEach((function(n){Object.defineProperty(e,n,Object.getOwnPropertyDescriptor(t,n))}))}return e}function i(e,n){if(null==e)return{};var t,r,o=function(e,n){if(null==e)return{};var t,r,o={},a=Object.keys(e);for(r=0;r<a.length;r++)t=a[r],n.indexOf(t)>=0||(o[t]=e[t]);return o}(e,n);if(Object.getOwnPropertySymbols){var a=Object.getOwnPropertySymbols(e);for(r=0;r<a.length;r++)t=a[r],n.indexOf(t)>=0||Object.prototype.propertyIsEnumerable.call(e,t)&&(o[t]=e[t])}return o}var c=r.createContext({}),p=function(e){var n=r.useContext(c),t=n;return e&&(t="function"==typeof e?e(n):s(s({},n),e)),t},l=function(e){var n=p(e.components);return r.createElement(c.Provider,{value:n},e.children)},d={inlineCode:"code",wrapper:function(e){var n=e.children;return r.createElement(r.Fragment,{},n)}},u=r.forwardRef((function(e,n){var t=e.components,o=e.mdxType,a=e.originalType,c=e.parentName,l=i(e,["components","mdxType","originalType","parentName"]),u=p(t),m=o,f=u["".concat(c,".").concat(m)]||u[m]||d[m]||a;return t?r.createElement(f,s(s({ref:n},l),{},{components:t})):r.createElement(f,s({ref:n},l))}));function m(e,n){var t=arguments,o=n&&n.mdxType;if("string"==typeof e||o){var a=t.length,s=new Array(a);s[0]=u;var i={};for(var c in n)hasOwnProperty.call(n,c)&&(i[c]=n[c]);i.originalType=e,i.mdxType="string"==typeof e?e:o,s[1]=i;for(var p=2;p<a;p++)s[p]=t[p];return r.createElement.apply(null,s)}return r.createElement.apply(null,t)}u.displayName="MDXCreateElement"},1976:(e,n,t)=>{t.r(n),t.d(n,{assets:()=>c,contentTitle:()=>s,default:()=>d,frontMatter:()=>a,metadata:()=>i,toc:()=>p});var r=t(7462),o=(t(7294),t(3905));const a={sidebar_position:1},s="Sender",i={unversionedId:"core-concepts/Sender",id:"core-concepts/Sender",title:"Sender",description:"Sender is a basic abstraction over the possibility to send single message. Its simplified definition looks like this:",source:"@site/../mdoc/target/mdoc/core-concepts/Sender.md",sourceDirName:"core-concepts",slug:"/core-concepts/Sender",permalink:"/pass4s/docs/core-concepts/Sender",draft:!1,editUrl:"https://github.com/ocadotechnology/pass4s/edit/main/docs/core-concepts/Sender.md",tags:[],version:"current",sidebarPosition:1,frontMatter:{sidebar_position:1},sidebar:"sidebar",previous:{title:"Getting started",permalink:"/pass4s/docs/getting-started"},next:{title:"Consumer",permalink:"/pass4s/docs/core-concepts/Consumer"}},c={},p=[],l={toc:p};function d(e){let{components:n,...t}=e;return(0,o.kt)("wrapper",(0,r.Z)({},l,t,{components:n,mdxType:"MDXLayout"}),(0,o.kt)("h1",{id:"sender"},"Sender"),(0,o.kt)("p",null,"Sender is a basic abstraction over the possibility to send single message. Its simplified definition looks like this:"),(0,o.kt)("pre",null,(0,o.kt)("code",{parentName:"pre",className:"language-scala"},"trait Sender[F[_], -A] extends (A => F[Unit]) with Serializable {\n\n  /** Sends a single message.\n    */\n  def sendOne(msg: A): F[Unit]\n\n  /** Alias for [[sendOne]]. Thanks to this, you can pass a Sender where a function type is expected.\n    */\n  def apply(msg: A): F[Unit] = sendOne(msg)\n\n}\n")),(0,o.kt)("p",null,"As you can see ",(0,o.kt)("inlineCode",{parentName:"p"},"Sender")," is basically a type of function of ",(0,o.kt)("inlineCode",{parentName:"p"},"A => F[Unit]")," shape. It comes with many combinators for mapping, filtering and combining ",(0,o.kt)("inlineCode",{parentName:"p"},"Sender"),"s, as well as ",(0,o.kt)("inlineCode",{parentName:"p"},"Functor")," and ",(0,o.kt)("inlineCode",{parentName:"p"},"Monoid")," instances."),(0,o.kt)("p",null,"Please refer to ",(0,o.kt)("a",{parentName:"p",href:"https://github.com/ocadotechnology/pass4s/blob/main/kernel/src/main/scala/com/ocadotechnology/pass4s/kernel/Sender.scala"},(0,o.kt)("inlineCode",{parentName:"a"},"Sender.scala")," sources")," and the scaladocs."))}d.isMDXComponent=!0}}]);