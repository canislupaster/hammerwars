import "preact/debug"
import { ComponentProps, render } from 'preact'
import { useEffect, useMemo, useReducer, useRef, useState } from "preact/hooks";
import ShadertoyReact from "shadertoy-react";

import { useAutoAnimate } from '@formkit/auto-animate/preact'
import "./index.css"
import fragShader from './shader.frag?raw'
import gifs from './gifs.txt?raw'

type Problem = {
	id: string,
	pts: number|null,
	url: string
};

type Submission = {
	pts: number,
	fullSolve: boolean,
	stat: {time: string, fastest: boolean, order: number, numSub: number}
};

type SubmissionData = Submission&{teamId: number, teamName: string, problem: string};

type Team = {
	name: string,
	pts: number,
	problemSubmissions: Record<string, Submission>
};

const contestStart = new Date("2024-03-16T14:38:19.603Z");
const minutes = (x: number) => new Date(contestStart.getTime() + 60*1000*x).toISOString();

const genMsg = [...new Array(10)].map((_,i) => ({
	teamId: i+2,
	teamName: `Team ${i+2}`,
	pts: 500,
	problem: "A",
	fullSolve: true,
	stat: {time: minutes(5), fastest: false, order: i+1, numSub: 0},
	t: i+3
}));

const testMsgs: ((SubmissionData|(Init&{submissions: SubmissionData[]}))&{t: number})[] = [
	{
		problems: [
			{id: "A", pts: 500, url: "a.com"},
			{id: "B", pts: 900, url: ""},
			{id: "Game", pts: null, url: ""}
		],
		submissions: [
			{
				teamId: 1,
				teamName: "Team B",
				pts: 0,
				problem: "B",
				fullSolve: false,
				stat: {time: minutes(2), fastest: false, order: 0, numSub: 1}
			},
		],
		startTime: "2024-03-16T14:38:19.603Z",
		endTime: "2024-03-16T23:38:19.000Z",
		t: 1
	},
	{
		teamId: 1,
		teamName: "Team B",
		pts: 500,
		problem: "A",
		fullSolve: true,
		stat: {time: minutes(5), fastest: true, order: 0, numSub: 0},
		t: 3
	}
	// {
	// 	teamId: 0,
	// 	teamName: "Team A",
	// 	pts: 0,
	// 	problem: "A",
	// 	fullSolve: false,
	// 	stat: {time: minutes(5), fastest: false, order: 1, numSub: 1},
	// 	t: 5
	// },
	// {
	// 	teamId: 1,
	// 	teamName: "Team B",
	// 	pts: 500,
	// 	problem: "A",
	// 	fullSolve: true,
	// 	stat: {time: minutes(10), fastest: false, order: 0, numSub: 0},
	// 	t: 10
	// },
	// {
	// 	teamId: 0,
	// 	teamName: "Team A",
	// 	pts: 500,
	// 	problem: "A",
	// 	fullSolve: true,
	// 	stat: {time: minutes(10), fastest: true, order: 1, numSub: 1},
	// 	t: 10
	// },
	// {
	// 	teamId: 1,
	// 	teamName: "Team B again",
	// 	pts: 990,
	// 	problem: "B",
	// 	fullSolve: true,
	// 	stat: {time: minutes(15), fastest: true, order: 0, numSub: 1},
	// 	t: 15
	// },
	// ...genMsg
];

const isTest=false;

type Event = {
	bonus: "fastest" | "first" | null,
	problem: string,
	pts: number,
	upsolve: boolean,
	team: string,
	gif: string
};

function useTimeUntil(when: string) {
	const [until, setUntil] = useState<number|null>();
	useEffect(() => {
		const d = new Date(when).getTime();
		let curTimeout: number|null = null;
		const cb = () => {
			const x = d-Date.now();
			if (x<0) setUntil(null);
			else {
				setUntil(Math.ceil(x/1000.0));
				curTimeout = setTimeout(cb, 1000-x%1000);
			}
		};

		cb();
		return () => {if (curTimeout!=null) clearTimeout(curTimeout);};
	}, [when]);

	return until;
}

function formatDur(t: number) {
	return `${Math.floor(t/3600)} h ${Math.floor((t%3600)/60)} m ${t%60} s`;
}

type Init = {problems: Problem[], startTime: string, endTime: string};

//maybe smarter later
const ptsStr = (x: number) => x.toFixed(2);

function Solve({sub,problem,init}: {sub?: Submission, problem: Problem, init: Init}) {
	if (sub==undefined) return <td></td>;

	const d = useMemo(() =>
		Math.floor((new Date(sub.stat.time).getTime()-new Date(init.startTime).getTime())/1000.0),
		[sub.stat.time, init.startTime]);

	let extra = [];

	if (problem.pts!=null) {
		if (sub.stat.fastest) extra.push("⏰");
		if (sub.stat.order==0) extra.push("🥇");
		else if (sub.stat.order<10) {
			extra.push(`#${sub.stat.order+1}`);
		}
	}

	extra.push(ptsStr(sub.pts));

	const extraEl = extra.map((x) => <span>{x}</span>)
		.reduce((a,b) => <>{a} <div className="vsep" ></div> {b}</>);

	return <td className={`solve ${sub.fullSolve ? "solved" : "unsolved"} ${sub.stat.fastest ? " fast" : ""}`} >
		<p className="count" >{sub.fullSolve ? "+" : "-"}{sub.stat.numSub==0 ? "" : `${sub.stat.numSub}`}</p>
		<p className="time" >{Math.floor(d/3600)}:{Math.floor((d/60)%60).toString().padStart(2,"0")}</p>
		{sub.fullSolve ? <div className="extra" >{extraEl}</div> : <></>}
	</td>;
}

function Scoreboard({teams,init,events}: {teams: Record<number, Team>, init: Init, events: Event[]}) {
	const untilStart = useTimeUntil(init.startTime);
	const untilEnd = useTimeUntil(init.endTime);

	const [eventI, setEventI] = useState(0);
	const [eventStart, setEvStart] = useState(0);
	const eventRef = useRef<HTMLDivElement|null>(null);

	useEffect(() => {
		if (eventI>=events.length) return;
		setEvStart(Date.now());

		console.log("starting anim");
		console.log(eventRef.current!!.animate([
			{opacity: 0}, {opacity: 1}
		], {duration: 500, easing: "ease-in-out"}));

		const timeout = setTimeout(() => {
			const anim = eventRef.current!!.animate([
				{opacity: 1}, {opacity: 0}
			], {duration: 1200, easing: "ease-in-out", fill: "forwards"});

			anim.addEventListener("finish", () => {
				setEventI(eventI+1);
			});
		}, 2500);

		return () => window.clearTimeout(timeout);
	}, [eventI, eventI==events.length]);

	const teamsSorted = useMemo(() =>
		Object.entries(teams).sort((a,b) => b[1].pts-a[1].pts), [teams]);

	const [parent] = useAutoAnimate((el, action, oldCoords, newCoords) => {
		let keyframes: Keyframe[]=[];

		if (action === "add") {
			keyframes = [
				{ transform: 'scale(0) skewX(0)', opacity: 0 },
				{ transform: 'scale(1.3) skewX(-5deg)', opacity: 1, offset: 0.75 },
				{ transform: 'scale(1) skewX(0)', opacity: 1 }
			];
		} else if (action === 'remain') {
			const deltaX = oldCoords!!.left - newCoords!!.left
			const deltaY = oldCoords!!.top - newCoords!!.top

			// set up our steps with our positioning keyframes
			const start = { transform: `translate(${deltaX}px, ${deltaY}px) scale(1) skewX(0)` }
			const mid = { transform: `translate(${deltaX * -0.5}px, ${deltaY * -0.5}px) scale(1.4) skewX(-5deg)`, offset: 0.75 }
			const end = { transform: `translate(0, 0) scale(1) skewX(0)` }

			keyframes=[start,mid,end];
		}

		return new KeyframeEffect(el, keyframes, { duration: 1500, easing: 'ease-in-out' });
	});

	const loadTime = useMemo(() => Date.now(), []);
	const cur: Event|null = eventI>=events.length ? null : events[eventI];

	if (untilStart!=null) return <p>
		Contest starts in {formatDur(untilStart)}
	</p>; else return <>
		<img src="/scoreboard.svg" id="hero" />

		{cur!=null ? <div class="modal" >
			<div id="event" key={eventI} ref={eventRef} >
				{cur.bonus!=null ? <h1>
					{cur.bonus=="fastest" ? "⏰ Fastest solution!" : "🥇 First blood!"}
				</h1> : <></>}

				<p><b>{cur.team}</b> {cur.upsolve ? "up" : ""}solved <b>{cur.problem}</b> for <b>{ptsStr(cur.pts)}</b> points</p>

				<iframe frameborder={0} allowTransparency={true} scrolling="no"
					src={`https://tenor.com/embed/${cur.gif}`} />
			</div>
		</div> : <></>}

		<p class="status" >{untilEnd!=null ? `Contest ends in ${formatDur(untilEnd)}` : `Contest has ended`}</p>

		<ShadertoyReact style={{
			width: "100vw", height: "100vh", position: "fixed", left: 0, top: 0, zIndex: -1
		}} fs={fragShader} uniforms={{
			evTime: {type: "1f", value: (eventStart-loadTime)/1000.0},
			seed: {type: "1f", value: (loadTime/1000.0)%5000.0}
		}} ></ShadertoyReact>

		<table className="teams" >
			<thead>
				<tr className="head" >
					<td>Team name</td>
					<td>Points</td>
					{init.problems.map((prob) =>
						<td>
							<a target="_blank" href={prob.url} >{prob.id}</a>
						</td>
					)}
				</tr>
			</thead>
			<tbody ref={parent} >
				{teamsSorted.map((x) => {
					return <tr key={x[0]} className="team" >
						<td class="teamname" >{x[1].name}</td>
						<td class="points" >{ptsStr(x[1].pts)}</td>
						{init.problems.map((prob) => 
							<Solve sub={x[1].problemSubmissions[prob.id]} problem={prob} init={init} />
						)}
					</tr>
				})}
			</tbody>
		</table>
	</>;
}

const gifIds: string[] = (gifs as string).trim().split("\n");

console.log(gifIds);

function App() {
	const [teams, setTeams] = useState<Record<number, Team>>({})
	const [init, setInit] = useState<Init|null>(null);
	const [events, setEvents] = useState<Event[]>([]);
	const [err, setErr] = useState<string|null>(null);

	const handleSolve = (subData: SubmissionData, silent: boolean, init: Init) => {
		setTeams((x) => {
			const cur: Omit<Team,"name"> = x[subData.teamId]==undefined ? {
				pts: 0, problemSubmissions: {}
			} : {...x[subData.teamId]};

			if (cur.problemSubmissions[subData.problem]!=undefined)
				cur.pts -= cur.problemSubmissions[subData.problem].pts;
			cur.pts += subData.pts;

			if (!silent) {
				const bonus = subData.stat.order==0 ? "first" : (subData.stat.fastest ? "fastest" : null);

				if (subData.fullSolve && init.problems.find((x) => x.id==subData.problem)?.pts!=null) {
					const psub = x[subData.teamId]?.problemSubmissions[subData.problem];
					if (psub==undefined || !psub.fullSolve || (!psub.stat.fastest && subData.stat.fastest))
						setEvents((evts) => [...evts, {
							team: subData.teamName, bonus,
							problem: subData.problem,
							gif: gifIds[Math.floor(Math.random()*gifIds.length)],
							pts: subData.pts,
							upsolve: psub!=undefined && psub.fullSolve
						}]);
				}
			}

			return {
				...x,
				[subData.teamId]: {
					...cur,
					name: subData.teamName,
					problemSubmissions: {
						...cur.problemSubmissions,
						[subData.problem]: subData
					}
				}
			};
		})
	};
	
	useEffect(() => {
		let loc = new URL("/scoreboard/ws", window.location.href);
		loc.protocol = loc.protocol.startsWith("https") ? "wss:" : "ws:";
		
		let ws: WebSocket;
		let reconnTimeout: number|null = null;
		let initRef: Init|null = null;

		const handleMsg = (obj: any) => {
			if (obj.hasOwnProperty("type") && obj.type=="error") {
				setErr(obj.message);
			} else if (obj.hasOwnProperty("problems")) {
				setInit(obj);
				initRef=obj;
				for (const sub of obj.submissions)
					handleSolve(sub, true, obj);
			} else {
				if (initRef==null) setErr("Bad state -- submission before initialized");
				else handleSolve(obj, false, initRef);
			}
		};
		
		let conn = () => {
			ws=new WebSocket(loc.href);
			reconnTimeout=null;
			
			const reconnInterval = 2000;

			ws.onopen = () => {
				setErr(null);
			};
			
			ws.onclose = () => {
				setErr("Websocket closed... reconnecting.")
				if (reconnTimeout==null) reconnTimeout = setTimeout(conn, reconnInterval);
			};
			
			ws.onerror = (e) => {
				setErr("Websocket error. Check console for details.")
				console.error(e);
				if (reconnTimeout==null) reconnTimeout = setTimeout(conn, reconnInterval);
			};
			
			ws.onmessage = (ev) => {
				const obj: any = JSON.parse(ev.data);
				handleMsg(obj);
			};
		};

		if (isTest) {
			let timeouts = [];
			for (let msg of testMsgs) {
				timeouts.push(window.setTimeout(() => handleMsg(msg), msg.t*1000));
			}

			return () => timeouts.forEach((x) => window.clearTimeout(x));
		} else {
			conn();
		}
		
		return () => {
			ws?.close();
			if (reconnTimeout!=null) clearTimeout(reconnTimeout);
		}
	},[]);

	let inner = <></>;
	if (err!=null) {
		inner=<>
			<h1>Error</h1>
			<p>{err}</p>
			<button class="btn" onClick={() => {setErr(null);}} >Dismiss</button>
		</>;
	} else if (init==null) {
		inner=<h1>Loading...</h1>;
	} else {
		inner=<Scoreboard init={init} teams={teams} events={events} />
	}
	
	return <div className="container" >
		{inner}
	</div>;
}

render(<App />, document.body)
