import React from 'react';
import { Container, Graphics, Text, withPixiApp } from '@inlet/react-pixi'
import * as PIXI from "pixi.js";

const RectWidth = 200;
const RectHeight = 100;

const RectFillColor = 0xFF3300;
const RectLineWidth = 4;
const RectLineColor = 0x66CCFF;

const VoteRPCStarted = 'VoteRPCStarted';
const VoteRPCReplied = 'VoteRPCReplied';
const VoteRPCEnded = 'VoteRPCEnded';
const Elected = 'Elected';
const AppendRPCStarted = 'AppendRPCStarted';
const AppendRPCReplied = 'AppendRPCReplied';
const AppendRPCEnded = 'AppendRPCEnded';
const LogCommitted = 'LogCommitted';

const colors = {
    VoteRPCStarted: 0x3366ff,
    VoteRPCReplied: 0x0000ff,
    VoteRPCEnded: 0x000099,
    AppendRPCStarted: 0xff0066,
    AppendRPCReplied: 0xff5050,
    AppendRPCEnded: 0xff0000,
};

const eventColor = event => {
    const color = colors[event.tpe]
    return color === undefined ? 0x00ff00 : color;
};

export const RaftEvent = withPixiApp(props => {
    return (
      <Container x={props.idx * RectWidth}>
        <Graphics draw={ g => {
            g.beginFill(eventColor(props.event));
            g.lineStyle(RectLineWidth, RectLineColor);
            g.drawShape(basicRect());
            g.endFill();
        }}/>
        <Text x={10} y={10} text={props.event.tpe}/>
      </Container>
    );

});

const sliderToPage = (sliderV, totalPage) => {
    const page = totalPage * sliderV;
    const targetPage = Math.floor(page);
    const offset = page % 1;
    return [targetPage, offset];
};

// page starts from zero
const pageToRange = (page, pageSize, total) => {
    const pgStart = page * pageSize;
    const pgEnd = Math.min(total, pgStart + pageSize);
    return [pgStart, pgEnd];
};

export const RaftEvents = withPixiApp((props) => {
    const totalE = props.events.length;
    const viewWidth = props.app.screen.width;
    const pageSize = viewWidth / RectWidth;
    const totalPage = totalE / pageSize;

    const [targetPg, pgOffsetPercent] = sliderToPage(props.sliderV, totalPage);

    const pgOffset = pgOffsetPercent * viewWidth;
    const [pageStart, pageEnd] = pageToRange(targetPg, pageSize, totalE);

    const position = new PIXI.Point(-pgOffset, 10);
    const events = props.events.slice(pageStart, pageEnd+pageSize);
    return (
      <Container position={position}>
        {
            events.map((ev, idx) =>
            <RaftEvent event={ev} key={idx} idx={idx}/>
          )
        }
      </Container>);

});

function basicRect() {
    const r = new PIXI.Rectangle();
    r.width = RectWidth;
    r.height = RectHeight;
    return r;
}
