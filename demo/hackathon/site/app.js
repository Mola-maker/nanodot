/* Nanodot judging-page interaction: entirely local, with preset data and replies. */
'use strict';
const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));
const canvas = $('#selection-canvas');
const ctx = canvas.getContext('2d');
const phone = $('.phone');
const orb = $('.demo-orb');
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
const targets = {
  bus: {name:'公交卡', explanation:'这里可以查看公交卡余额和出行记录。实际应用中，先核对卡号与记录，再决定是否进行下一步操作。'},
  appointment: {name:'预约办事', explanation:'这里可以先选择办事时间，再去现场办理。先找到要办的事项，选择合适的时间，最后核对信息。'},
  bill: {name:'缴费查询', explanation:'这里可以区分待缴和已缴记录。实际应用中，请先核对项目、金额与收款方，再决定是否付款。'}
};
let shape = 'circle';
let selected = 'appointment';
let points = [];
let drawing = false;
let animation = 0;
let generation = 0;
let careStep = -1;
let careStopped = false;
const careScreens = [
 {title:'今天想做什么？', subtitle:'第一步，找到「预约办事」', name:'预约办事', detail:'点击这项，进入预约流程', instruction:'第 1 步：找到「预约办事」。蓝色轨迹会指出它的位置。看清后点击「我已完成」。'},
 {title:'选择一个时间', subtitle:'第二步，选择方便的时段', name:'周三 · 10:00', detail:'演示时段，不会产生真实预约', instruction:'第 2 步：选择一个方便的时间。这里用「周三 · 10:00」做示意。确认后再继续。'},
 {title:'再核对一次', subtitle:'第三步，先看清，再确认', name:'核对预约信息', detail:'时间、事项、地点都确认了吗？', instruction:'第 3 步：核对事项、时间和地点。现实场景中，确认无误后才提交。此网页不会提交预约。'}
];
function targetBox(key) {
 const element = $(`[data-target="${key}"]`) || $('.sample-card');
 const r=element.getBoundingClientRect(), c=canvas.getBoundingClientRect();
 return {x:(r.left-c.left)/c.width*360-7,y:(r.top-c.top)/c.height*560-7,w:r.width/c.width*360+14,h:r.height/c.height*560+14};
}
function clearCanvas() { ctx.clearRect(0,0,360,560); }
function positionOrb(p) {orb.style.left=`${p.x/360*100}%`;orb.style.top=`${p.y/560*100}%`;orb.style.right='auto';}
function drawPath(path, close=false) {
 clearCanvas(); if(!path.length)return;
 ctx.strokeStyle='#377ac9';ctx.lineWidth=3.7;ctx.lineCap='round';ctx.lineJoin='round';ctx.shadowBlur=5;ctx.shadowColor='#8ebeff';
 ctx.beginPath();ctx.moveTo(path[0].x,path[0].y);path.slice(1).forEach(p=>ctx.lineTo(p.x,p.y));
 if(close)ctx.closePath();ctx.stroke();ctx.shadowBlur=0;positionOrb(path[path.length-1]);
}
function shapePath(box, kind=shape) {
 const {x,y,w,h}=box;
 if(kind==='circle')return Array.from({length:73},(_,i)=>{const a=i/72*Math.PI*2;return{x:x+w/2+Math.cos(a)*w/2,y:y+h/2+Math.sin(a)*h/2};});
 const corners=kind==='rectangle'?[{x,y},{x:x+w,y},{x:x+w,y:y+h},{x,y:y+h},{x,y}]:[{x:x+8,y:y+4},{x:x+w*.65,y:y-3},{x:x+w,y:y+9},{x:x+w-4,y:y+h*.6},{x:x+w*.85,y:y+h*.64},{x:x+w*.87,y:y+h},{x:x+w*.38,y:y+h+3},{x:x+1,y:y+h*.83},{x:x-5,y:y+h*.35},{x:x+8,y:y+4}];
 const path=[];corners.slice(0,-1).forEach((a,i)=>{const b=corners[i+1];for(let j=0;j<12;j++)path.push({x:a.x+(b.x-a.x)*j/12,y:a.y+(b.y-a.y)*j/12});});path.push(corners[corners.length-1]);return path;
}
function cancelAnimation(){generation++;cancelAnimationFrame(animation);animation=0;}
function animatePath(path) {
 cancelAnimation();const mine=generation;
 if(reducedMotion.matches){drawPath(path,true);return;}
 const started=performance.now();
 const tick=(time)=>{if(mine!==generation)return;const count=Math.min(path.length,Math.max(2,Math.ceil((time-started)/1350*path.length)));drawPath(path.slice(0,count),count===path.length);if(count<path.length)animation=requestAnimationFrame(tick);};
 animation=requestAnimationFrame(tick);
}
function selectTarget(key, animate=true) {
 selected=key;
 $$('[data-preset]').forEach(button=>{const active=button.dataset.preset===key;button.classList.toggle('selected',active);button.setAttribute('aria-pressed',String(active));});
 $('#selection-status').textContent=`已选择：${targets[key].name} · ${shape==='circle'?'圆形':shape==='rectangle'?'矩形':'自由轨迹'}`;
 if(animate)animatePath(shapePath(targetBox(key)));else drawPath(shapePath(targetBox(key)),true);
}
function addMessage(text,role='assistant') {const element=document.createElement('div');element.className=`message ${role}`;element.textContent=text;$('#chat-log').append(element);$('#chat-log').scrollTop=$('#chat-log').scrollHeight;}
function endCareOnSelection() { if(careStep>=0)stopCare('已退出分步演示，回到自由圈选。'); }
$$('[data-shape]').forEach(button=>button.addEventListener('click',()=>{shape=button.dataset.shape;$$('[data-shape]').forEach(b=>{const active=b===button;b.classList.toggle('selected',active);b.setAttribute('aria-pressed',String(active));});if(careStep>=0){animatePath(shapePath(targetBox('appointment')));}else selectTarget(selected);}));
$$('[data-preset]').forEach(button=>button.addEventListener('click',()=>{endCareOnSelection();selectTarget(button.dataset.preset);}));
function coordinate(event){const r=canvas.getBoundingClientRect();return{x:Math.max(0,Math.min(360,(event.clientX-r.left)/r.width*360)),y:Math.max(0,Math.min(560,(event.clientY-r.top)/r.height*560))};}
canvas.addEventListener('pointerdown',(event)=>{if(event.button!==0)return;endCareOnSelection();cancelAnimation();drawing=true;points=[coordinate(event)];canvas.setPointerCapture(event.pointerId);drawPath(points);});
canvas.addEventListener('pointermove',(event)=>{if(!drawing)return;points.push(coordinate(event));if(shape==='rectangle'){const a=points[0],b=points[points.length-1];drawPath(shapePath({x:Math.min(a.x,b.x),y:Math.min(a.y,b.y),w:Math.abs(a.x-b.x),h:Math.abs(a.y-b.y)},'rectangle'),true);}else drawPath(points);});
function finishPointer(event){if(!drawing)return;drawing=false;const last=coordinate(event);if(canvas.hasPointerCapture(event.pointerId))canvas.releasePointerCapture(event.pointerId);if(points.length<4){const nearest=closestTarget(last.x,last.y);selectTarget(nearest);return;}const xs=points.map(p=>p.x),ys=points.map(p=>p.y);const box={x:Math.min(...xs),y:Math.min(...ys),w:Math.max(...xs)-Math.min(...xs),h:Math.max(...ys)-Math.min(...ys)};const key=closestTarget(box.x+box.w/2,box.y+box.h/2);selectTarget(key,false);if(shape==='rectangle')drawPath(shapePath(box,'rectangle'),true);else drawPath(points,true);$('#selection-status').textContent=`圈选完成，示例目标：${targets[key].name}（按区域中心匹配预设内容）`;}
canvas.addEventListener('pointerup',finishPointer);
canvas.addEventListener('pointercancel',()=>{drawing=false;points=[];clearCanvas();$('#selection-status').textContent='手势已取消，可重新圈选。';});
function closestTarget(x,y){return Object.keys(targets).reduce((best,key)=>{const b=targetBox(key),distance=Math.hypot(x-(b.x+b.w/2),y-(b.y+b.h/2));return distance<best.distance?{key,distance}:best;},{key:'appointment',distance:Infinity}).key;}
$('#question-form').addEventListener('submit',event=>{event.preventDefault();const input=$('#question');const question=input.value.trim()||'这里是做什么的？';addMessage(question,'user');addMessage(`「${targets[selected].name}」：${targets[selected].explanation}\n这是预设解释，不是 AI 对你的问题实时生成的回答。`);input.value='';});
function showCareStep(){
 const s=careScreens[careStep];$('#screen-title').textContent=s.title;$('#screen-subtitle').textContent=s.subtitle;
 const cards=$('#sample-cards');cards.replaceChildren();
 const button=document.createElement('button');button.type='button';button.className='sample-card';button.dataset.target='appointment';
 const icon=document.createElement('span');icon.className='card-icon blue';icon.textContent=careStep===0?'▦':careStep===1?'◷':'✓';
 const copy=document.createElement('span'),strong=document.createElement('strong'),small=document.createElement('small');strong.textContent=s.name;small.textContent=s.detail;copy.append(strong,small);button.append(icon,copy);button.addEventListener('click',nextCare);cards.append(button);
 $('#care-status').textContent=`关怀示意 · 第 ${careStep+1} / 3 步`;
 $('#selection-status').textContent=`关怀示意目标：${s.name}`;
 $('#next-care').textContent=careStep===2?'完成示意':'我已完成';
 addMessage(s.instruction);animatePath(shapePath(targetBox('appointment')));
}
function startCare(){cancelAnimation();selectTarget('appointment',false);careStep=0;careStopped=false;$('#care-controls').hidden=false;$('#start-care').hidden=true;showCareStep();}
function nextCare(){if(careStep<0||careStopped)return;if(careStep>=2){cancelAnimation();clearCanvas();careStep=-1;$('#care-status').textContent='演示结束 · 没有提交真实预约';$('#next-care').disabled=true;$('#repeat-care').disabled=true;addMessage('完成了三步交互示意。你始终可以查看说明、重复高亮或停止。真实场景中的任务结果仍需独立核验。');return;}careStep++;showCareStep();}
function restoreCards(){
 $('#screen-title').textContent='今天想做什么？';$('#screen-subtitle').textContent='选择一项服务，看看下一步';
 const cards=$('#sample-cards');cards.replaceChildren();Object.entries(targets).forEach(([key,item],i)=>{const button=document.createElement('button');button.type='button';button.className='sample-card';button.dataset.target=key;const icon=document.createElement('span');icon.className=`card-icon ${['lavender','blue','green'][i]}`;icon.textContent=['▰','▦','≡'][i];const copy=document.createElement('span');const strong=document.createElement('strong');strong.textContent=item.name;const small=document.createElement('small');small.textContent=['余额与出行记录','先预约，再出门','查看待缴与已缴记录'][i];copy.append(strong,small);const arrow=document.createElement('span');arrow.textContent='›';arrow.setAttribute('aria-hidden','true');button.append(icon,copy,arrow);button.addEventListener('click',()=>selectTarget(key));cards.append(button);});
}
function stopCare(message='已停止。引导轨迹已清除，你可以自由选择下一步。') {careStopped=true;careStep=-1;cancelAnimation();clearCanvas();restoreCards();$('#care-controls').hidden=true;$('#start-care').hidden=false;$('#next-care').disabled=false;$('#repeat-care').disabled=false;$('#selection-status').textContent='关怀示意已停止，圈选已清除。';addMessage(message);}
$('#start-care').addEventListener('click',startCare);
$('#next-care').addEventListener('click',nextCare);
$('#repeat-care').addEventListener('click',()=>{if(careStep>=0&&!careStopped){animatePath(shapePath(targetBox('appointment')));addMessage(careScreens[careStep].instruction);}});
$('#stop-care').addEventListener('click',()=>stopCare());
$('#reset-demo').addEventListener('click',()=>{stopCare();selected='appointment';$('#chat-log').replaceChildren();addMessage('你圈中了「预约办事」。它可以帮你先选时间，再去现场办理。想了解下一步吗？');$('#question').value='';selectTarget(selected);});
$$('[data-target]').forEach(button=>button.addEventListener('click',()=>selectTarget(button.dataset.target)));
$$('[data-time]').forEach(button=>button.addEventListener('click',()=>{const video=$('#android-video');video.currentTime=Number(button.dataset.time);video.play().catch(()=>{});video.focus();}));
window.addEventListener('resize',()=>{if(drawing)return;cancelAnimation();if(careStep>=0)drawPath(shapePath(targetBox('appointment')),true);else if(!careStopped)selectTarget(selected,false);});
requestAnimationFrame(()=>selectTarget(selected,false));
/* The signed v1.1.0 release and all three downloadable assets were verified by the release coordinator. */
const release = {published:true,url:'https://github.com/Mola-maker/nanodot/releases/download/v1.1.0/Nanodot-1.1.0-arm64.apk'};
if(release.published){const link=document.createElement('a');link.id='apk-download';link.className='button white';link.href=release.url;link.textContent='下载 Android 预览版 ↓';$('#apk-download').replaceWith(link);$('#release-status').textContent='1.1.0 Crystal · 签名 APK';}
