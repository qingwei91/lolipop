/**
 * entry.js
 * 
 * This is the first file loaded. It sets up the Renderer, 
 * Scene and Camera. It also starts the render loop and 
 * handles window resizes.
 * 
 */

import * as PIXI from 'pixi.js';

window.GOWN = {};
window.GOWN.loader = PIXI.Loader.shared;

import Slider from './shaded-gown/Slider';
import ThemeParser from './shaded-gown/ThemeParser';

import events from './data/events.json';
import {drawEventsPerNode} from './raft-viz.js';

const app = new PIXI.Application({
  autoResize: true,
  resolution: devicePixelRatio
});

document.body.appendChild(app.view);
const loader = PIXI.Loader.shared;

const rects = events['0'];

loader.load(setup);

function setup() {
  const graphic = new PIXI.Graphics();

  const metalTheme = new ThemeParser("aeon.json");

  const slider = new Slider(metalTheme);
  slider.x = 400;
  slider.width = 300;


  const eventContainer = drawEventsPerNode(rects, graphic.geometry);

  // let rectangle = new PIXI.Graphics();
  // rectangle.lineStyle(4, 0xFF3300, 1);
  // rectangle.beginFill(0x66CCFF);
  // rectangle.drawRect(0, 0, 64, 64);
  // rectangle.endFill();
  // rectangle.x = 170;
  // rectangle.y = 170;
  app.stage.addChild(eventContainer);
  app.stage.addChild(slider);


  // app.ticker.add(delta => gameLoop(delta));
  function gameLoop(delta){

  }
}

window.addEventListener('resize', resize);

function resize() {
  // Resize the renderer
  app.renderer.resize(window.innerWidth, window.innerHeight);
}

resize();
