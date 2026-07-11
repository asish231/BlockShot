package com.blockshot;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/**
 * LEGACY reference implementation: a dependency-free Java/Swing software renderer.
 * It is no longer the active game — see {@link GpuBlockShot} for the current GPU
 * (LWJGL/OpenGL) open-world edition. Kept only as a historical/reference demo.
 */
public final class BlockShot {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("BlockShot 3D");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setContentPane(new World()); f.pack(); f.setLocationByPlatform(true); f.setVisible(true);
        });
    }

    static final class World extends JPanel implements ActionListener, KeyListener, MouseMotionListener, MouseListener {
        static final int W=1100,H=700; static final double NEAR=.12, FOCAL=620;
        final boolean[] key=new boolean[256]; final List<Box> world=new ArrayList<>(); final List<Enemy> enemies=new ArrayList<>();
        final javax.swing.Timer loop=new javax.swing.Timer(16,this); double x=0,y=1.75,z=-7,yaw=0,pitch=0; int oldMX=-1,oldMY=-1, health=100, score=0, ammo=12, reserve=48, cooldown=0, reload=0; boolean lost=false,won=false;
        World(){ setPreferredSize(new Dimension(W,H)); setFocusable(true); addKeyListener(this); addMouseListener(this); addMouseMotionListener(this); makeWorld(); loop.start(); }
        void makeWorld(){
            world.clear(); enemies.clear();
            for(int gx=-11;gx<=11;gx++) for(int gz=-4;gz<=19;gz++) world.add(new Box(gx,-.55,gz,1,.55,1, ((gx+gz)&1)==0?new Color(91,157,75):new Color(79,143,69)));
            // path, buildings, trees, villagers
            for(int gz=-3;gz<19;gz++) world.add(new Box(-.55,-.48,gz,1.1,.09,1,new Color(204,174,113)));
            house(-8,4,new Color(210,164,96),new Color(171,69,48)); house(5,10,new Color(151,188,207),new Color(69,90,154)); house(-6,14,new Color(221,197,146),new Color(114,71,46));
            tree(-8,0);tree(7,1);tree(9,7);tree(-5,9);tree(7,16);tree(-9,16);tree(3,3);tree(2,15);
            villager(-3,5,new Color(246,179,86)); villager(3,8,new Color(96,177,243)); villager(-2,13,new Color(239,109,153));
            enemy(5,4,new Color(240,80,75)); enemy(-6,9,new Color(227,165,61)); enemy(6,15,new Color(171,86,238)); enemy(-8,17,new Color(60,204,169));
        }
        void house(double bx,double bz,Color wall,Color roof){
            world.add(new Box(bx,0,bz,3,2.2,3,wall)); world.add(new Box(bx-.22,2.2,bz-.22,3.44,.7,3.44,roof));
            world.add(new Box(bx+1.15,0,bz-.02,.05,.9,.65,new Color(76,51,37)));
            world.add(new Box(bx+.28,1.12,bz-.03,.05,.55,.65,new Color(106,205,231)));
        }
        void tree(double tx,double tz){ world.add(new Box(tx-.16,0,tz-.16,.32,2,.32,new Color(93,59,32))); world.add(new Box(tx-1.05,1.55,tz-1.05,2.1,1.6,2.1,new Color(51,122,59))); world.add(new Box(tx-.7,3.1,tz-.7,1.4,.7,1.4,new Color(70,151,68))); }
        void villager(double vx,double vz,Color shirt){ world.add(new Box(vx-.25,0,vz-.18,.5,.8,.36,new Color(45,61,106))); world.add(new Box(vx-.3,.8,vz-.22,.6,.72,.44,shirt)); world.add(new Box(vx-.24,1.52,vz-.19,.48,.48,.38,new Color(232,174,121))); }
        void enemy(double ex,double ez,Color c){ enemies.add(new Enemy(ex,ez,c)); }
        @Override public void actionPerformed(ActionEvent e){ if(!lost&&!won) update(); repaint(); }
        void update(){
            double speed= key[KeyEvent.VK_SHIFT]? .12:.075; double fx=Math.sin(yaw),fz=Math.cos(yaw),sx=Math.cos(yaw),sz=-Math.sin(yaw);
            if(key['w']||key['W']){x+=fx*speed;z+=fz*speed;} if(key['s']||key['S']){x-=fx*speed;z-=fz*speed;} if(key['a']||key['A']){x-=sx*speed;z-=sz*speed;} if(key['d']||key['D']){x+=sx*speed;z+=sz*speed;}
            x=Math.max(-10,Math.min(10,x)); z=Math.max(-3,Math.min(18,z)); if(cooldown>0)cooldown--; if(reload>0&&--reload==0){int n=Math.min(12-ammo,reserve);ammo+=n;reserve-=n;}
            for(Enemy en:enemies) en.tick(); if(enemies.isEmpty())won=true; if(health<=0)lost=true;
        }
        void shoot(){ if(cooldown>0||reload>0||ammo==0||lost||won)return; ammo--;cooldown=10; Enemy hit=null; double best=999;
            for(Enemy e:enemies){ double dx=e.x-x,dz=e.z-z,dist=Math.hypot(dx,dz); double angle=Math.abs(Math.atan2(dx,dz)-yaw); angle=Math.min(angle,Math.PI*2-angle); if(angle<.085 && Math.abs(y-1.1)<2 && dist<best){hit=e;best=dist;} }
            if(hit!=null){hit.hp--;if(hit.hp==0){enemies.remove(hit);score+=100;}}
        }
        void reload(){if(reload==0&&ammo<12&&reserve>0)reload=55;}
        @Override protected void paintComponent(Graphics raw){ super.paintComponent(raw); Graphics2D g=(Graphics2D)raw.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(99,185,239));g.fillRect(0,0,W,H/2);g.setColor(new Color(217,239,255));g.fillOval(820,55,75,75);g.setColor(new Color(187,220,239));g.fillRect(0,H/2,W,H/2);
            List<Face> faces=new ArrayList<>(); for(Box b:world)addBox(faces,b); for(Enemy e:enemies)e.add(faces); faces.sort(Comparator.comparingDouble(a->-a.depth)); for(Face f:faces)f.draw(g);
            hud(g); crosshair(g); if(lost||won)end(g); g.dispose(); }
        void addBox(List<Face> fs,Box b){double X=b.x,Y=b.y,Z=b.z,w=b.w,h=b.h,d=b.d; Vec[] p={new Vec(X,Y,Z),new Vec(X+w,Y,Z),new Vec(X+w,Y+h,Z),new Vec(X,Y+h,Z),new Vec(X,Y,Z+d),new Vec(X+w,Y,Z+d),new Vec(X+w,Y+h,Z+d),new Vec(X,Y+h,Z+d)}; int[][] q={{0,1,2,3},{5,4,7,6},{4,0,3,7},{1,5,6,2},{3,2,6,7}}; for(int i=0;i<q.length;i++)fs.add(new Face(new Vec[]{p[q[i][0]],p[q[i][1]],p[q[i][2]],p[q[i][3]]}, shade(b.c,i)));}
        Color shade(Color c,int side){double m= side==4?1.1:side==1?.72:side==0?.84:.93;return new Color((int)Math.min(255,c.getRed()*m),(int)Math.min(255,c.getGreen()*m),(int)Math.min(255,c.getBlue()*m));}
        void hud(Graphics2D g){g.setColor(new Color(8,15,24,210));g.fillRoundRect(16,15,310,70,14,14);g.setColor(Color.WHITE);g.setFont(new Font(Font.MONOSPACED,Font.BOLD,18));g.drawString("BLOCKSHOT 3D",31,42);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,15));g.drawString("DRONES "+enemies.size()+"   SCORE "+score,31,68);g.setColor(new Color(8,15,24,210));g.fillRoundRect(826,15,255,70,14,14);g.setColor(new Color(72,220,120));g.fillRoundRect(844,28,health*130/100,14,8,8);g.setColor(Color.WHITE);g.drawString("HP "+health,985,42);g.drawString("AMMO "+ammo+" / "+reserve+(reload>0?"  RELOAD":""),844,69);}
        void crosshair(Graphics2D g){g.setColor(Color.WHITE);g.setStroke(new BasicStroke(2));int cx=W/2,cy=H/2;g.drawLine(cx-12,cy,cx-4,cy);g.drawLine(cx+4,cy,cx+12,cy);g.drawLine(cx,cy-12,cx,cy-4);g.drawLine(cx,cy+4,cx,cy+12);}
        void end(Graphics2D g){g.setColor(new Color(0,0,0,160));g.fillRect(0,0,W,H);g.setColor(won?new Color(108,255,149):new Color(255,101,101));g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,48));String s=won?"VILLAGE SECURED":"YOU WERE ELIMINATED";int sw=g.getFontMetrics().stringWidth(s);g.drawString(s,(W-sw)/2,310);g.setColor(Color.WHITE);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,21));s="Press Enter to restart";g.drawString(s,(W-g.getFontMetrics().stringWidth(s))/2,350);}
        final class Enemy {double x,z;int hp=3,cool=50;Color c;Enemy(double x,double z,Color c){this.x=x;this.z=z;this.c=c;}void tick(){if(--cool<=0&&Math.hypot(x-World.this.x,z-World.this.z)<10){health-=8;cool=75;}}void add(List<Face> f){addBox(f,new Box(x-.38,0,z-.38,.76,1.45,.76,c));addBox(f,new Box(x-.28,1.45,z-.28,.56,.38,.56,new Color(240,173,122)));}}
        final class Face {Vec[] v;Color c;double depth;Face(Vec[]v,Color c){this.v=v;this.c=c;double t=0;for(Vec a:v)t+=cam(a).z;depth=t/v.length;}void draw(Graphics2D g){int[] xs=new int[v.length],ys=new int[v.length];for(int i=0;i<v.length;i++){Vec p=cam(v[i]);if(p.z<NEAR)return;xs[i]=(int)(W/2+p.x/p.z*FOCAL);ys[i]=(int)(H/2-p.y/p.z*FOCAL);}g.setColor(c);g.fillPolygon(xs,ys,v.length);g.setColor(c.darker());g.drawPolygon(xs,ys,v.length);}}
        Vec cam(Vec p){double dx=p.x-x,dy=p.y-y,dz=p.z-z;double a=Math.cos(yaw),b=Math.sin(yaw);double cx=a*dx-b*dz,cz=b*dx+a*dz;double cp=Math.cos(pitch),sp=Math.sin(pitch);return new Vec(cx,cp*dy-sp*cz,sp*dy+cp*cz);}
        record Vec(double x,double y,double z){} record Box(double x,double y,double z,double w,double h,double d,Color c){}
        @Override public void keyPressed(KeyEvent e){int k=e.getKeyCode();if(k<key.length)key[k]=true;if(k==KeyEvent.VK_R)reload();if(k==KeyEvent.VK_ENTER&&(lost||won)){health=100;score=0;ammo=12;reserve=48;cooldown=reload=0;lost=won=false;x=0;y=1.75;z=-7;yaw=0;makeWorld();}}
        @Override public void keyReleased(KeyEvent e){int k=e.getKeyCode();if(k<key.length)key[k]=false;} @Override public void keyTyped(KeyEvent e){}
        @Override public void mouseMoved(MouseEvent e){if(oldMX>=0){yaw+=(e.getX()-oldMX)*.006;pitch=Math.max(-.6,Math.min(.6,pitch+(e.getY()-oldMY)*.004));}oldMX=e.getX();oldMY=e.getY();} @Override public void mouseDragged(MouseEvent e){mouseMoved(e);} @Override public void mousePressed(MouseEvent e){shoot();requestFocusInWindow();} @Override public void mouseReleased(MouseEvent e){} @Override public void mouseClicked(MouseEvent e){} @Override public void mouseEntered(MouseEvent e){oldMX=-1;requestFocusInWindow();} @Override public void mouseExited(MouseEvent e){oldMX=-1;}
    }
}
