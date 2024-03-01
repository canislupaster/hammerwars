const defaultMsgs =
    `<h1>Amazing prizes, free food, and one of the few times you'll actually enjoy coding</h1>
    HammerWars is back!
    Team up with two others to tackle difficult challenges
    because going alone would spell certain doom
    Everyone is welcome, regardless of skill or major — no competitive programming experience needed
    ... but it might help ;)
    This year, we'll throw some unconventional challenges into the mix
    in addition to your standard epic Codeforces contest
    @ LILY, Apr. 7, 2-7 PM. 1-3 per team.
    by the <a href="https://purduecpu.github.io/" ><b>Competitive Programming Union</b></a>.`
        .split("\n").map((x,i) =>
            ({type: "server", style: i%2==0 ? "big" : "normal", content: x.trim()}));

let current = -1;

let msgs = [];
let root;

let cursorElem = null;
let inputElem = null;
let inputInner = [];

let invisForm;
let invisInput;

function append(child) {
    if (typeof child == "string") invisForm.insertAdjacentText("beforebegin", child);
    else invisForm.insertAdjacentElement("beforebegin", child);
}

function addMsg(msg, render=false) {
    const elem = document.createElement("span");
    elem.classList.add("msg", `term-${msg.style}`, `term-${msg.type}`);

    if (inputElem) {
        inputElem.remove();
        inputElem=null; inputInner=null;
    }

    let ev = [];
    if (render) {
        elem.innerText = msg.content;
        current = msgs.length;
        append(elem);
        append("\n");
        moveCursor();
    } else {
        elem.innerHTML = msg.content;

        let t = (elem) => {
            for (const e of elem.childNodes) {
                if (e.nodeType==Node.TEXT_NODE) {
                    ev.push(...e.textContent.split("[pause]")
                        .map((x) =>
                            ({type: "text", content: x})
                        ).reduce((a,b) =>
                            a.length>0 ? [...a, {type: "pause"}, b] : [b], []
                        )
                    );
                } else {
                    ev.push({type: "start", node: e.cloneNode(false)});
                    t(e);
                    ev.push({type: "end"});
                }
            }
        };

        t(elem);
        elem.innerHTML="";
    }

    msgs.push({...msg, ev, elem});
}

function addInputLine() {
    const inputElemPre = document.createElement("span");

    inputElemPre.textContent = inputInner.length>0 ? "\n> " : "> ";
    inputElemPre.classList.add("term-input-pre");

    inputElem.appendChild(inputElemPre);

    inputInner.push({
        span: document.createElement("span"),
        indents: [],
        pre: inputElemPre
    });

    inputElem.appendChild(inputInner[inputInner.length-1].span);
}

function removeInputLine() {
    if (inputInner.length>1) {
        const last = inputInner.pop();

        last.span.remove();
        last.pre.remove();
        last.indents.forEach((x) => x.remove());
    }
}

function indentInput(more) {
    const last = inputInner[inputInner.length-1];
    if (more) {
        const id = document.createElement("span");
        id.classList.add("term-indent");
        last.indents.push(id);
        last.pre.appendChild(id);
    } else if (last.indents.length>0) {
        last.indents.pop().remove();
    }
}

function moveCursor() {
    if (cursorElem) cursorElem.remove();

    cursorElem = document.createElement("span");
    cursorElem.textContent = "█";
    cursorElem.classList.add("cursor");

    if (current == msgs.length) {
        cursorElem.classList.add("blink");

        if (!inputElem) {
            inputElem = document.createElement("span");
            inputInner=[];
            addInputLine();

            inputElem.classList.add("msg");
            inputElem.classList.add("term-input");
            append(inputElem);

            const rect = invisInput.getBoundingClientRect();
            if (rect.top > window.innerHeight && rect.bottom > window.innerHeight)
                invisInput.focus();
        }

        inputElem.appendChild(cursorElem);
    } else {
        msgs[current].elem.appendChild(cursorElem);
    }

    root.scrollTo(0, root.scrollHeight);
}

const step = 3, wait=3;
let inPause=-1;

function update() {
    if (current >= msgs.length || --inPause > 0) return;

    if (current!=-1 && msgs[current].ev.length>0) {
        let eaten = 0;
        while (eaten < step && msgs[current].ev.length>0) {
            const ev = msgs[current].ev[0];
            if (ev.type=="text") {
                const len = Math.min(step-eaten, ev.content.length);
                msgs[current].elem.append(ev.content.slice(0,len));
                msgs[current].ev[0].content = ev.content.slice(len);
                if (msgs[current].ev[0].content.length==0) msgs[current].ev.shift();
                eaten += len;
            } else if (ev.type=="pause") {
                inPause=wait;
                msgs[current].ev.shift();
                break;
            } else if (ev.type=="start") {
                msgs[current].elem.appendChild(ev.node);
                msgs[current].elem = ev.node;
                msgs[current].ev.shift();
            } else if (ev.type=="end") {
                msgs[current].elem = msgs[current].elem.parentElement;
                msgs[current].ev.shift();
            }
        }

        if (msgs[current].ev.length==0) inPause=wait;
    }

    if (inPause<=0 && (current==-1 || msgs[current].ev.length==0)) {
        current++;

        append("\n");
        if (current<msgs.length) append(msgs[current].elem);
    }

    moveCursor();
}

const interpreter = new Interpreter();

let buf = "";
interpreter.print_function = (text, eol) => {
    buf+=text.toString();
    if (eol) {
        addMsg({type: "server", style: "normal", content: String(buf)});
        buf="";
    }
};

let inputting = null;
interpreter.string_input_function = (p) => {inputting="string"; return null;};
interpreter.number_input_function = (p) => {inputting="number"; return null};

interpreter.clear_function = () => {
    msgs.forEach((x) => x.elem.remove());
    msgs=[];
    current=-1;
};

window.addEventListener("DOMContentLoaded", () => {
    root = document.getElementById("msgs");
    defaultMsgs.forEach((x) => addMsg(x));

    invisForm = document.createElement("form");
    invisInput = document.createElement("input");
    invisInput.style.cssText=
        `width: 0;
        height: 0;
        background: transparent;
        border: transparent;
        color: transparent;
        outline: none;`;

    invisForm.appendChild(invisInput);
    invisForm.action = "javascript:void(0)";
    root.appendChild(invisForm);

    root.addEventListener("click", () => invisInput.focus());
    root.addEventListener("focusin", () => invisInput.focus());

    invisInput.addEventListener("input", (e) => {
        if (current < msgs.length) return;

        let last = inputInner[inputInner.length-1].span;
        last.textContent += e.data;

        moveCursor();
    })

    invisInput.addEventListener("keydown", (e) => {
        if (current < msgs.length) return;
        const last = inputInner[inputInner.length-1].span;

        if (e.key == "Backspace") {
            if (last.textContent=="") {
                if (inputInner.length>0 && inputInner[inputInner.length-1].indents.length>0)
                    indentInput(false);
                else removeInputLine();
            } else {
                last.textContent = last.textContent.slice(0, -1);
            }
        } else if (e.key=="Enter" && e.shiftKey) {
            addInputLine();
            e.preventDefault();
        } else if (e.key=="Tab") {
            indentInput(!e.shiftKey);
            e.preventDefault();
        } else return;

        moveCursor();
    });

    invisForm.addEventListener("submit", (e) => {
        if (current < msgs.length) return;

        const input = inputInner.map((x) =>
            `${"\t".repeat(x.indents.length)}${x.span.textContent.trim()}`
        ).join("\n").trim();

        if (input.length>0) {
            addMsg({type: "user", style: "normal", content: input}, true);

            try {
                if (inputting!=null) {
                    const inp = inputting=="string" ? input : Number.parseFloat(input);
                    interpreter.push_input(inp);
                    interpreter.resume_input();
                    inputting=null;
                } else {
                    const p = new Parser(input);
                    p.parse();
                    interpreter.setParser(p);
                    interpreter.interpret();
                }
            } catch (error) {
                addMsg({type: "server", style: "error", content: error.toString()});
            }
        }
    });

    setInterval(update, 50);
});