/**
 * entry.js
 * 
 * This is the first file loaded. It sets up the Renderer, 
 * Scene and Camera. It also starts the render loop and 
 * handles window resizes.
 * 
 */

import * as PIXI from 'pixi.js';


const app = new PIXI.Application({width: 512, height: 512});

document.body.appendChild(app.view);
const loader = PIXI.Loader.shared;

const catImgPath = "img/cat.jpg";

loader
  .add(catImgPath)
  .load(setup);

function setup() {

  //Create the cat sprite
  const cat = new PIXI.Sprite(loader.resources[catImgPath].texture);
  cat.x = 128;
  cat.y = 128;
  //Add the cat to the stage
  app.ticker.add(delta => gameLoop(delta));
  app.stage.addChild(cat);
  function gameLoop(delta){

    //Move the cat 1 pixel
    cat.x += 1;
  }
}



