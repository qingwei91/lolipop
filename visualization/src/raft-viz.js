import * as PIXI from 'pixi.js';

const VoteRPCStarted = 'VoteRPCStarted';
const VoteRPCReplied = 'VoteRPCReplied';
const VoteRPCEnded = 'VoteRPCEnded';

const RectWidth = 200;
const RectHeight = 100;

const RectFillColor = 0xFF3300;
const RectLineWidth = 4;
const RectLineColor = 0x66CCFF;

function basicRect() {
  const r = new PIXI.Rectangle();
  r.width = RectWidth;
  r.height = RectHeight;
  return r;
}

function drawEvent(idx, event, geometric) {
  const container = new PIXI.Container();
  const graphic = new PIXI.Graphics(geometric);
  const text = new PIXI.Text(event.tpe, {
    fontSize: 20
  });

  text.position = new PIXI.Point(10, 10);

  const rec = basicRect();

  graphic.beginFill(RectFillColor);
  graphic.lineStyle(RectLineWidth, RectLineColor);
  graphic.drawShape(rec);
  graphic.endFill();
  container.addChild(graphic);
  container.addChild(text);
  return container;
}


export function drawEventsPerNode(events, geometric) {

  const container = new PIXI.Container();
  events.slice(0,3).map((v, idx) => {
    const eventContainer = drawEvent(idx, v, geometric);

    // move the container so that they layout horizontally
    eventContainer.position = new PIXI.Point(idx * eventContainer.width , eventContainer.y);

    container.addChild(eventContainer);

  });
  return container;
}
