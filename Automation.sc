/*
   Automation is a SuperCollider Quark.
   (c) 2009 Neels J. Hofmeyr <neels@hofmeyr.de>, GNU GPL v3 or later.

   Automation allows to record and playback live changes made to
   other GUI elements in particular, or anything else you wish to
   setup.

   Please see the HelpSource/Tutorials/AutomationIntro.schelp and
   HelpSource/Classes/Automation.schelp files.

*/

Automation {
    var <>length,
        <>presetDir,
        <>onPlay, <>onStop, <>onSeek,
        <>playLatency = 0.1, <>seekLatency = 0.1,
        <>doStopOnSeek = false,
        <>onEnd,
        <>verbose = true,
        <>server,
        <>doDefer = true,
        <doRecord = false,
        <clients,
        <>gui,
        <>showLoadSave = true,
        <>showSnapshot = true,
        <>minTimeStep = 0.01,
        startTime = -1, startOffset = 0,
        semaphore = nil,
        playRoutine,
        playStatus = false,
        playDoReschedule = true,
        playLastStopped = 0;

    *new { |length=180, server=nil, showLoadSave=true, showSnapshot=true, minTimeStep=0.01|
        ^super.new.constructor(length, server, showLoadSave, showSnapshot, minTimeStep);
    }

    front { |win=nil, bounds=nil|
        AutomationGui(this, win, bounds);
        ^this;
    }

    dock {|guiElement, name=nil|
        // safeguard the control's own elements
        if ( this.isMyGuiElement(guiElement) ) {
            if (verbose){
                ("Automation: Refusing to dock one of my own control"+
                  "elements as a client.").postln;
            };
        }{
            // nothing wrong here, add it.
            if (name == nil) {
                name = ("automated" ++ (clients.size + 1));
            };
            AutomationClient(guiElement, this, name);
        };
    }

    isMyGuiElement {|guiElement|
        if (gui != nil){
            ^gui.isMyGuiElement(guiElement);
        }{
            ^false;
        };
    }

    findAndDock {|list|
        var classname;
        list.do{|child|
            classname = "" ++ child.class;
            if (classname.containsi("button")
                || classname.containsi("slider")
                || classname.containsi("numberbox")
                || classname.containsi("checkbox")
                || classname.containsi("textfield")){
                this.dock(child);
            // Note: we're not passing a name to dock(), so the control
            // elements will be auto-named by order of appearance. That
            // means, altering a GUI and then loading a previously saved
            // automation will most probably load some or all values into the
            // wrong GUI elements, depending on whether their order changed.
            // (However, if you can always dock newer GUI elements after the
            // older ones, this won't be a problem.)
            // Note that the order of appearance is not necessarily the
            // visual order in the GUI.
            };
        };
    }

    play {
        var waitTime;
        fork{
            semaphore.wait;
            if (playStatus == false){
                startOffset = this.now;
                startTime = -1;
                playDoReschedule = true;

                // make sure we've got enough time elapsed after
                // the routine was last running.
                waitTime = this.clockTime - playLastStopped;
                if (waitTime < 0.2){
                    waitTime =  min(0.2, max(0.2 - waitTime, 0.01) );
                    waitTime.wait;
                };

                playRoutine.reset;
                playRoutine.play;
            };
            semaphore.signal;
        };
    }

    seek { |seconds=0.0, dostop=nil|
        fork{
            if (dostop == nil) {
                dostop = doStopOnSeek;
            };

            semaphore.wait;
            this.privateSeek(seconds, dostop);
            semaphore.signal;
        };
    }

    stop {
        fork{
            semaphore.wait;
            this.privateStop( this.now );
            semaphore.signal;
        };
    }

    quit {
        fork{
            semaphore.wait;
            if (startTime >= 0){
                startOffset = 0;
                startTime = -1;

                // call user supplied function
                onStop.value(startOffset);
            };
            semaphore.signal;
        };
    }

    snapshot {
        var now;
        now = max(0.0, this.now);
        this.defer{
            clients.do {|client|
                client.snapshot(now);
            };
        };
    }

    save{|dir|
        presetDir = dir;
        ("mkdir -p" + dir).systemCmd;
        if (verbose){
            ("Automation: Saving controls to" + dir + "...").postln;
        };
        clients.do{|client| client.save(dir); };
        if (verbose){
            "Automation: ...saving done.".postln;
        };
    }

    load {|dir|
        presetDir = dir;
        if (verbose){
            ("Automation: Loading controls from `" ++ dir ++ "'...").postln;
        };
        clients.do{|client| client.load(dir); };
        if (verbose){
            "Automation: ...Loading done.".postln;
        };
    }

    now {
        if (startTime < 0){
            // startTime < 0 means we're not playing.
            // in that case the startOffset holds our current position.
            ^startOffset;
        }{
            // we're playing, so we need to use the startTime and
            // startOffset to determine our current position right NOW.
            ^(this.clockTime - startTime + startOffset);
        };
    }

    constructor { |ilength, iserver, ishowLoadSave, ishowSnapshot, iminTimeStep|
        // evaluate input args

        server = iserver;
        length = 0.0 + ilength; // make sure it is a float

        showLoadSave = ishowLoadSave;
        showSnapshot = ishowSnapshot;
        minTimeStep = iminTimeStep;
        if (server == nil){
            server = Server.default;
        };


        clients = List.new;
        semaphore = Semaphore(1);

        onEnd = {
            length = length * 1.2; // +20%
        };

        // this is the bigass routine that takes care of
        // launching and scheduling playback.
        playRoutine = Routine{
            var now,
                events,
                nextEventTime,
                nextTimeSlider,
                visitClient, stopPlayback,
                aclient, step,
                req, reqarg,
                nowdisp;

            // the events queue
            events = SortedList(0, {|itemA, itemB| itemA[0] < itemB[0]});

            // the internal function to nudge a client to action and
            // get the next time it wants to be nudged, scheduling it
            // in the events queue.
            visitClient = {|iclient, inow|
                var nextTime;
                nextTime = iclient.bang(inow);
                if ((nextTime >= inow) && (nextTime < inf)){
                    events.add( [nextTime, iclient] );
                };
            };

            // enter/lock semaphore
            semaphore.wait;

            // start playback and set status
            this.privatePlay;
            playStatus = true;

            // run
            block{|break| loop{
                // semaphore is still (or again) locked

                now = this.now;

                // external stop carried out?
                if (startTime < 0) { break.value; };

                // someone changed the current timing positions?
                if (playDoReschedule){
                    playDoReschedule = false;

                    // skip to another time position; redo the events queue.
                    events.clear;

                    clients.do{|client|
                        visitClient.value(client, now);
                    };

                    nextTimeSlider = -1;
                };

                // do clients need action?
                block{|break2| loop{
                    if (events.size < 1) { break2.value };
                    if (now >= events[0][0]) {
                        aclient = events[0][1];
                        events.removeAt(0);

                        visitClient.value(aclient, now);
                    }{
                        break2.value
                    };
                }};

                // does the timeslider need action?
                if (now >= nextTimeSlider){
                    // we display the time slider in a fixed resolution.
                    nowdisp = now.round(0.25);
                    if (gui != nil){
                        this.defer{ gui.updateTimeGUI(nowdisp); };
                    };

                    // in that resolution, this is the next time that we
                    // need time slider action.
                    nextTimeSlider = nowdisp + 0.25;

                    if ((length - now) > 0){
                        // normal course of action.
                        // ensure range of the next timeslider event does
                        // not exceed the slider range.
                        nextTimeSlider = max(0, min(nextTimeSlider, length));
                    }{
                        // the slider knob reached its end.
                        // call the user supplied function.
                        onEnd.value;

                        // sanity check.
                        // Have we reached the end and no action
                        // has been taken? Panic and stop everything.
                        if (((length - this.now) <= 0) &&
                            (startTime > 0)
                           ) {
                            this.privateStop( this.now );
                            break.value;
                        };
                    };
                };

                // determine the next soonest event
                if (events.size > 0) {
                    nextEventTime = events[0][0];
                }{
                    nextEventTime = inf;
                };

                // determine the waiting time from that
                step = min(nextEventTime, nextTimeSlider) - now;

                // exit/unlock semaphore
                semaphore.signal;

                // do the waiting
                if (step > 0.0){
                    step = min( max(0.005, step), 0.25);
                    step.wait;
                };

                // enter/lock semaphore
                semaphore.wait;
            }}; // loop

            // exiting. semaphore is still locked.
            // notify status "stopped", and the time of stopping.
            playStatus = false;
            playLastStopped = this.clockTime;

            // exit/unlock semaphore
            semaphore.signal;

            // goodbye.
        }; // playRoutine

    } // constructor()

    defer { |func|
        if (doDefer){
            func.defer;
        }{
            func.value;
        }
    }

    clockTime {
        ^thisThread.seconds;
    }

    privatePlay {
        // Let's give the onPlay() function a head start.
        startTime = this.clockTime + playLatency;
        server.makeBundle(playLatency, {
            onPlay.value(startOffset);
        });

        playDoReschedule = true;

        playStatus = \playing;

        if (gui != nil){
            this.defer{
                gui.unblock;
                gui.setPlaying(true);
                gui.updateTimeGUI(startOffset);
            };
        };
    }

    privateStop {|seekPos|
        startOffset = seekPos;
        startTime = -1;

        // call user supplied function
        onStop.value(startOffset);

        clients.do{|client|
            client.bang(startOffset);
        };

        if (gui != nil){
            this.defer{
                gui.unblock;
                gui.setPlaying(false);
                gui.updateTimeGUI(startOffset);
            };
        };
    }

    privateSeek {|seconds, dostop|
        startOffset = seconds;
        playDoReschedule = true;

        if (startTime > 0){
            if (dostop.not){
                // currently playing. Restart playing at different position,
                // giving the onSeek function a headstart.
                startTime = this.clockTime + seekLatency;

                server.makeBundle(seekLatency, {
                    onSeek.value(startOffset);
                });
            }{
                // we're playing but we need to stop (dostop is true)
                startTime = -1;
                if (gui != nil){
                    this.defer{ gui.setPlaying(false); };
                };

                // essentially, we're both seeking and stopping.  but its
                // cumbersome for users to call two callbacks in the same
                // place. Plus, onStop also gets the seeking position.
                onStop.value(startOffset);
            };
        }{
            // not playing. Just call it without timing involved.
            onSeek.value(startOffset);
        };

        clients.do{|client|
            client.seek(startOffset);
        };

        if (gui != nil){
            this.defer{
                gui.unblock;
                gui.updateTimeGUI(startOffset);

                if (doRecord){
                    gui.setRecording(1);
                };
            };
        };
    }

    enableRecording{
        fork{
            semaphore.wait;
            doRecord = true;
            if (gui != nil){
                this.defer{
                    gui.setRecording(1);
                };
            };
            semaphore.signal;
        };
    }

    stopRecording{
        fork{
            semaphore.wait;
            doRecord = false;
            clients.do{|client|
                client.stopRecording;
            };
            semaphore.signal;
            if (gui != nil){
                this.defer{
                    gui.setRecording(0);
                };
            };
        };
    }

    clientStartsRecordingMsg {
        if (gui != nil){
            this.defer{
                gui.setRecording(2);
            };
        };
    }

    addClient {|autoClient|
        block{|break|
            clients.do{|client|
                if (client.name == autoClient.name){
                    ("Automation: WARNING! DUPLICATE AutomationClient.name `" ++ client.name ++ "', SAVING WILL FAIL!").postln;
                    break.value;
                };
            };
        };

        clients.add(autoClient);
        if (verbose){
            ("Automation: Added client `" ++ autoClient.name ++ "' as "
             ++ autoClient.valueKind.class).postln;
        };
    }
}
