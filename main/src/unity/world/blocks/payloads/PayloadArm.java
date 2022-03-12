package unity.world.blocks.payloads;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.payloads.PayloadBlock.*;
import unity.annotations.Annotations.*;
import unity.util.*;
import unity.util.GraphicUtils.*;
import unity.world.blocks.*;
import unity.world.blocks.distribution.DriveBelt.*;
import unity.world.graph.*;

import static mindustry.Vars.tilesize;
import static unity.world.blocks.payloads.PayloadArm.PayloadArmBuild.*;

public class PayloadArm extends GenericGraphBlock{
    public float range = 3.5f;
    public float maxSize = 8*8;
    public float moveTime = 60;
    public float rotateTime = 30;
    public int armjoints = 1;

    TextureRegion[] rotateIcons = new TextureRegion[4];
    TextureRegion[] armsegments;
    TextureRegion base,top,claw;

    public PayloadArm(String name){
        super(name);
        configurable = true;
        sync = true;
        rotate = true;
        solid = true;
        config(Point2[].class, (PayloadArmBuild build, Point2[] value) -> {
            build.from = value[0];
            build.to = value[1];
            build.rotateTargetBy = Math.abs(value[2].x) + Math.abs(value[2].y);
            build.recalcPositions();
            build.switchState(build.state);
        });
        config(int[].class,(PayloadArmBuild build, int[] value) -> {
            switch(value[0]){
                case SEL_IN:
                    build.from.set(value[1],value[2]);
                    build.recalcPositions();
                    switch(build.state){
                        case MOVINGTOPICKUP, PICKINGUP -> build.switchState(ArmState.MOVINGTOPICKUP);
                    }
                    break;
                case SEL_OUT:
                    build.to.set(value[1],value[2]);
                    build.recalcPositions();
                    switch(build.state){
                        case MOVINGTOTARGET:
                        case DROPPING:
                        case ROTATINGTARGET:
                            build.switchState(ArmState.MOVINGTOTARGET);
                    }
                    break;
                case 2:
                    build.rotateTargetBy ++;
                    if(build.rotateTargetBy>3){
                        build.rotateTargetBy = 0;
                    }
                    build.growAnim = 0;
                    break;
            }
        });
    }

    @Override
    public void load(){
        super.load();
        for(int i = 0;i<rotateIcons.length;i++){
            rotateIcons[i] = Core.atlas.find("unity-rotate"+(i+1));
        }
        armsegments = new TextureRegion[armjoints+1];
        for(int i = 0;i<armsegments.length;i++){
            armsegments[i] = loadTex("seg"+(i));
        }
        base = loadTex("base");
        top = loadTex("top");
        claw = loadTex("claw");
    }

    @Override
    public void drawPlace(int x, int y, int rotation, boolean valid){
        super.drawPlace(x, y, rotation, valid);
        Tile tile = Vars.world.tile(x, y);
        if (tile != null){
            Lines.stroke(1.0F);
            Draw.color(Pal.placing);
            Drawf.circles((float)(x * 8) + this.offset, (float)(y * 8) + this.offset, this.range * 8.0F);
        }
    }

    public class PayloadArmBuild extends GenericGraphBuild{
        enum ArmState{
            PICKINGUP, MOVINGTOTARGET, ROTATINGTARGET, DROPPING, MOVINGTOPICKUP
        }

        public float progress, progressInterop;
        public float armBaseRotation,armExtend,payloadRotation;
        public float targetArmBaseRotation,targetArmExtend,targetpayloadRotation;
        Vec2[] calculatedPositions = new Vec2[2];
        public Point2 from,to;
        public int rotateTargetBy = 0;
        ArmState state = ArmState.PICKINGUP;
        public Payload carrying;

        public transient boolean selected = false;
        public transient int ioselect = -1;
        final static int SEL_IN = 0;
        final static int SEL_OUT = 1;

        public transient float growAnim = 0;

        public transient ZipperArm arm;
        public transient float clawopen = 0;

        @Override
        public void init(){
            super.init();
            if(from==null){
                from = new Point2(-Geometry.d4x(rotation),-Geometry.d4y(rotation));
                to = new Point2(Geometry.d4x(rotation),Geometry.d4y(rotation));
                recalcPositions();
                targetArmBaseRotation = armBaseRotation = (180+rotdeg())%360;
                targetArmExtend = armExtend = tilesize;
            }
            clipSize = Math.max(clipSize, (range) * tilesize + 2);
            arm = new ZipperArm(0,0,1,1,range*tilesize+4,armjoints);
            Events.on(EventType.TapEvent.class, e->{
                var thisbuild = PayloadArmBuild.this;
                if(!selected){
                    if(Vars.control.input.frag.config.getSelectedTile()==thisbuild){
                        deselect();
                    }
                    return;
                }
                if(e.tile == null ||e.tile.dst(tile)>range*tilesize){
                    selected = false;
                    deselect();
                    ioselect = -1;
                    return;
                }
                Point2 relpt = new Point2(e.tile.x-tile.x,e.tile.y-tile.y);
                if(ioselect!=-1){
                    configure(new int[]{ioselect,relpt.x,relpt.y});
                    ioselect = -1;
                }else{
                    if(relpt.equals(from)){
                        ioselect = SEL_IN;
                    }else if(relpt.equals(to)){
                        ioselect = SEL_OUT;
                    }else if(relpt.equals(0,0)){
                        configure(new int[]{2,relpt.x,relpt.y});
                    }else{
                        selected = false;
                        deselect();
                    }
                }
            });
        }

        private void setTarget(Vec2 tar){
            armBaseRotation = targetArmBaseRotation;
            armExtend = targetArmExtend;
            targetArmBaseRotation = tar.x;
            targetArmExtend = tar.y;
        }

        public void recalcPositions(){
            Tmp.v1.set((tile.x+from.x)*tilesize-x,(tile.y+from.y)*tilesize-y);
            calculatedPositions[0] = new Vec2(Mathf.atan2(Tmp.v1.x,Tmp.v1.y)*Mathf.radiansToDegrees,Tmp.v1.len());
            Tmp.v1.set((tile.x+to.x)*tilesize-x,(tile.y+to.y)*tilesize-y);
            calculatedPositions[1] = new Vec2(Mathf.atan2(Tmp.v1.x,Tmp.v1.y)*Mathf.radiansToDegrees,Tmp.v1.len());
        }

        @Override
        public void drawSelect(){
            if(!selected){
                super.drawSelect();
                float[] poss = new float[]{(tile.x + from.x) * tilesize, (tile.y + from.y) * tilesize, (tile.x + to.x) * tilesize, (tile.y + to.y) * tilesize};
                GraphicUtils.selected(poss[0], poss[1],1, Color.cyan);
                GraphicUtils.selected(poss[2], poss[3],1, Color.orange);
                float offset = Time.globalTime*0.03f%1.0f;
                float i1,i2,r,r2,l,l2;
                for(float i = 0; i<=1;i+=0.1){
                    i1 = Mathf.clamp(i + offset*0.1f);
                    i2 = Mathf.clamp(i + offset*0.1f+0.05f);
                    r = Mathf.lerp(calculatedPositions[0].x,calculatedPositions[1].x,i1);
                    r2 = Mathf.lerp(calculatedPositions[0].x,calculatedPositions[1].x,i2);
                    l = Mathf.lerp(calculatedPositions[0].y,calculatedPositions[1].y,i1);
                    l2 = Mathf.lerp(calculatedPositions[0].y,calculatedPositions[1].y,i2);
                    Lines.line(x+Mathf.cosDeg(r)*l,y+Mathf.sinDeg(r)*l,x+Mathf.cosDeg(r2)*l2,y+Mathf.sinDeg(r2)*l2);
                }


                Lines.stroke(1.0F);
                Draw.color(Pal.placing);
                Drawf.circles(x, y, range * 8.0F);
                Draw.color();
            }
        }


        @Override
        public void drawConfigure(){
            super.drawConfigure();
            float[] poss = new float[]{(tile.x + from.x) * tilesize, (tile.y + from.y) * tilesize, (tile.x + to.x) * tilesize, (tile.y + to.y) * tilesize};
            float s1 = Math.abs(Mathf.sin(Time.globalTime*0.1f));
            float s2 = s1;
            if(ioselect==0){
                s1 = -0.5f;
            }
            if(ioselect==1){
                s2 = -0.5f;
            }
            growAnim += (1-growAnim)*0.3f;
            GraphicUtils.selected(poss[0], poss[1],s1, Color.cyan);
            GraphicUtils.selected(poss[2], poss[3],s2, Color.orange);
            if(rotateTargetBy!=0){
                Draw.rect(rotateIcons[rotateTargetBy-1],poss[2], poss[3],growAnim*tilesize,growAnim*tilesize);
            }
            Lines.stroke(1.0F);
            Draw.color(Pal.placing);
            Drawf.circles(x, y, range * 8.0F);
            Draw.color();
            Drawf.shadow(x,y,8);
            Draw.rect(rotateIcons[3],x,y,growAnim*tilesize,growAnim*tilesize,growAnim*180);
        }

        public void switchState(ArmState state){
            this.state=state;

            switch(state){
                case ROTATINGTARGET:
                    payloadRotation = carrying.rotation();
                    targetpayloadRotation = carrying.rotation()+rotateTargetBy*90;
                case MOVINGTOTARGET:
                    setTarget(calculatedPositions[1]);
                    break;
                case DROPPING:
                    payloadRotation = targetpayloadRotation;
                    break;
                case PICKINGUP:
                case MOVINGTOPICKUP:
                    setTarget(calculatedPositions[0]);
                    break;
            }
            progress = 0;
        }

        public float getCurrentArmRotation(){
            return Mathf.lerp(armBaseRotation,targetArmBaseRotation,progressInterop);
        }
        public float getCurrentArmExtend(){
            return Mathf.lerp(armExtend,targetArmExtend,progressInterop);
        }

        @Override
        public void updateTile(){
            super.updateTile();
            if(selected){
                if(Vars.control.input.frag.config.getSelectedTile() != this || !Vars.control.input.frag.config.isShown()){
                    Vars.control.input.frag.config.showConfig(this); // force config to be open....
                }
            }
            TorqueGraphNode tnode = torqueNode();
            TorqueGraph tgraph = tnode.getGraph();
            switch(state){
                case PICKINGUP:
                    if(carrying==null){
                        if(tgraph.lastVelocity/tnode.maxSpeed<0.1){
                            break;
                        }
                        Tile t = Vars.world.tile(tile.x+from.x,tile.y+from.y);
                        if(t.build!=null){
                            if(t.build.block.outputsPayload || t.build instanceof PayloadBlockBuild){
                                //theres a payload block to recieve from...
                                //make sure its in range of the arm first.
                                if(t.build.getPayload() !=null && Mathf.sqr(t.build.getPayload().size())<=maxSize+0.01f && t.build.getPayload().dst((tile.x+from.x)*tilesize,(tile.y+from.y)*tilesize)<5){
                                    carrying = t.build.takePayload();
                                    payloadRotation = carrying.rotation();
                                    targetpayloadRotation= carrying.rotation();
                                    switchState(ArmState.MOVINGTOTARGET);
                                }
                            }else if(t.build.block.size * t.build.block.size * 8 * 8 <= maxSize){
                                ///theres a block we can grab directly...
                                Building build = t.build;
                                build.pickedUp();
                                if(!Vars.net.client()){
                                    build.tile.remove(); // buildings should not modify the world on client, for that results in desyncs.
                                }
                                build.afterPickedUp();
                                carrying = new BuildPayload(build);
                                payloadRotation = build.rotdeg();
                                targetpayloadRotation= build.rotdeg();
                                Fx.unitPickup.at(t);
                                switchState(ArmState.MOVINGTOTARGET);
                                break;
                            }
                        }
                        //maybe theres a unit nearby owo ....
                        var unit = Units.closest(this.team,(tile.x+from.x)*tilesize,(tile.y+from.y)*tilesize,7,(u)-> u.isAI() && u.isGrounded());
                        if(unit == null || Mathf.sqr(unit.hitSize())>maxSize+0.01f){
                            break;
                        }
                        if(!Vars.net.client()){
                            unit.remove(); // buildings should not modify the world on client, for that results in desyncs.
                        }
                        carrying = new UnitPayload(unit);
                        payloadRotation = carrying.rotation();
                        targetpayloadRotation= carrying.rotation();
                        Fx.unitPickup.at(unit);
                        if(Vars.net.client()){
                            Vars.netClient.clearRemovedEntity(unit.id);
                        }
                        switchState(ArmState.MOVINGTOTARGET);
                        break;
                    }else{
                        switchState(ArmState.MOVINGTOTARGET);
                    }
                    break;
                case MOVINGTOTARGET:
                    progress += Time.delta * Mathf.clamp(tgraph.lastVelocity/tnode.maxSpeed)/moveTime;
                    if(progress>=1){
                        progress = 1;
                        switchState(ArmState.ROTATINGTARGET);
                    }
                    break;
                case ROTATINGTARGET:
                    progress += Time.delta * Mathf.clamp(tgraph.lastVelocity/tnode.maxSpeed)/(rotateTime);
                    if(carrying instanceof BuildPayload buildp){
                        if(!buildp.block().rotate){
                            switchState(ArmState.DROPPING);
                            break;
                        }
                    }
                    if(rotateTargetBy == 0){
                        switchState(ArmState.DROPPING);
                    }
                    if(progress>=1){
                        progress = 1;
                        if(carrying instanceof BuildPayload buildp){
                            buildp.build.rotation += rotateTargetBy;
                        }else if(carrying instanceof UnitPayload unitp){
                            //unitp.unit.rotation
                        }
                        switchState(ArmState.DROPPING);
                    }
                    break;
                case DROPPING:
                    if(carrying ==null){
                        //uh.
                        switchState(ArmState.MOVINGTOPICKUP);
                    }
                    Tile t = Vars.world.tile(tile.x+to.x,tile.y+to.y);
                    if(t.build!=null){
                        //theres a payload block to push to...
                        if(t.build.acceptPayload(this, carrying)){
                            t.build.handlePayload(this, carrying);
                            carrying = null;
                            switchState(ArmState.MOVINGTOPICKUP);
                        }
                    }else{
                        Vec2 targetout = new Vec2((tile.x+to.x)*tilesize, (tile.y+to.y)*tilesize);
                        if(carrying instanceof UnitPayload unitp){
                            carrying.set(targetout.x,targetout.y, carrying.rotation());
                            if(unitp.dump()){
                                Fx.unitDrop.at(targetout.x,targetout.y);
                                switchState(ArmState.MOVINGTOPICKUP);
                                carrying = null;
                            }
                        }else if(carrying instanceof BuildPayload buildp){
                            if(Build.validPlace(buildp.block(), buildp.build.team, to.x+tile.x,to.y+tile.y, buildp.build.rotation, false )){ // place on the ground
                                buildp.place(t, buildp.build.rotation);
                                Fx.placeBlock.at(targetout.x, targetout.y, buildp.block().size);
                                switchState(ArmState.MOVINGTOPICKUP);
                                carrying = null;
                            }
                        }
                    }
                    break;
                case MOVINGTOPICKUP:
                    progress += Time.delta * Mathf.clamp(tgraph.lastVelocity/tnode.maxSpeed)/moveTime;
                    if(progress>=1){
                        progress = 1;
                        switchState(ArmState.PICKINGUP);
                    }
                    break;
            }
            progressInterop = Utils.interp(0,1,Mathf.clamp(progress));
            arm.start.set(0,0);
            arm.end.set( Mathf.cosDeg(getCurrentArmRotation())*getCurrentArmExtend(),Mathf.sinDeg(getCurrentArmRotation())*getCurrentArmExtend());
            arm.update();
            clawopen += ((carrying==null?1:0)-clawopen)*0.1f;

            if(carrying!=null){
                carrying.set(
                arm.end.x+x,
                arm.end.y+y,
                Mathf.lerp(payloadRotation,targetpayloadRotation,progressInterop));
            }
        }


        @Override
        public void draw(){
            Draw.rect(base,x,y,this.get2SpriteRotationVert());
            Draw.z(Layer.power-1);
            if(carrying!=null){
                if(carrying instanceof BuildPayload bp && (state.equals(ArmState.ROTATINGTARGET) || bp.block() instanceof Conveyor)){
                    bp.drawShadow(1.0F);
                    bp.build.tile = Vars.emptyTile;
                    Draw.rect(bp.icon(),bp.x(),bp.y(),bp.build.payloadRotation + bp.build.rotdeg());
                }else{
                    carrying.draw();
                }
            }
            Draw.z(Layer.power);
            for(int i =0;i<4;i++){
                float ang = i*90 + Mathf.lerp(payloadRotation,targetpayloadRotation,progressInterop);
                Draw.rect(claw,arm.end.x+x + Mathf.cosDeg(ang)*clawopen, arm.end.y+y + Mathf.sinDeg(ang)*clawopen,ang);
            }
            Lines.stroke(4);
            for(int i = 0;i<arm.joints;i++){
                if(i==0){
                    Lines.line(armsegments[0],arm.start.x+x, arm.start.y+y, arm.jointPositions[0].x+x, arm.jointPositions[0].y+y,true);
                }
                if(i==arm.joints-1){
                    Lines.line(armsegments[i+1],arm.end.x+x, arm.end.y+y, arm.jointPositions[i].x+x, arm.jointPositions[i].y+y,false);
                }else{
                    Lines.line(armsegments[i],arm.jointPositions[i].x+x, arm.jointPositions[i].y+y, arm.jointPositions[i+1].x+x, arm.jointPositions[i+1].y+y,true);
                }
            }
            Drawf.spinSprite(top,x,y,getCurrentArmRotation());
            drawTeamTop();
        }

        public ArmState getState(){
            return state;
        }

        @Override
        public boolean onConfigureTileTapped(Building other){
            return false;
        }

        @Override
        public boolean configTapped(){
            selected = true;
            return super.configTapped();
        }

        @Override
        public Payload getPayload(){
            return carrying;
        }

        @Override
        public void write(Writes write){
            super.write(write);
            Payload.write(carrying, write);
            write.f(progress);
            write.i(from.pack());
            write.i(to.pack());
            write.s(rotateTargetBy);
            write.s(state.ordinal());
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            carrying = Payload.read(read);
            progress = read.f();
            from = Point2.unpack(read.i());
            to = Point2.unpack(read.i());
            recalcPositions();
            rotateTargetBy = read.s();
            switchState(ArmState.values()[read.s()]);
        }

        @Override
        public Point2[] config(){
           Point2[] out = new Point2[3];
           out[0] = from.cpy();
           out[1] = to.cpy();
           out[2] = new Point2(0,rotateTargetBy);
           return out;
        }
    }
}