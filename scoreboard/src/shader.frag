//by mu6k
//License Creative Commons Attribution-NonCommercial-ShareAlike 3.0 Unported License.
//
//muuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuusk!

const float PI = 3.1415926535897932384626433832795;

float hash(float x)
{
	return fract(sin(cos(x*12.13)*19.123)*17.321);
}


float noise(vec2 p)
{
	vec2 pm = mod(p,1.0);
	vec2 pd = p-pm;
	float v0=hash(pd.x+pd.y*41.0);
	float v1=hash(pd.x+1.0+pd.y*41.0);
	float v2=hash(pd.x+pd.y*41.0+41.0);
	float v3=hash(pd.x+pd.y*41.0+42.0);
	v0 = mix(v0,v1,smoothstep(0.0,1.0,pm.x));
	v2 = mix(v2,v3,smoothstep(0.0,1.0,pm.x));
        return mix(v0,v2,smoothstep(0.0,1.0,pm.y));
}

// https://github.com/hughsk/glsl-hsv2rgb/blob/master/index.glsl
vec3 hsv2rgb(vec3 c) {
  vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
  vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

const float RES = 900.0;

const vec2 imgSize = vec2(300,300);

bool draw(float t, vec2 sub, vec2 uv, vec2 vel, float mul, inout vec4 fragColor) {
    t+=70.0;
    vec2 cp = mod(vel*t, 2.0*sub);
    
    if (cp.x > sub.x) cp.x = 2.0*sub.x - cp.x;
    if (cp.y > sub.y) cp.y = 2.0*sub.y - cp.y;

    cp += imgSize/2.0;
    vec2 dif = (uv-cp)/imgSize;
    float O = (vel.x*4.0 + vel.y*9.0)*t/iResolution.x;
    dif *= mat2(cos(O),-sin(O),sin(O),cos(O));
    if (length(dif)<=0.5) {
        vec4 samp = texture(iChannel0, dif + vec2(0.5,0.5));
        if (samp.a>0.0) {
          fragColor = (1.0-mul)*fragColor + mul*samp;
          return true;
        }
    }
    
    return false;
}

void draw1(float t, vec2 sub, vec2 uv, vec2 vel, inout vec4 fragColor) {
    if(draw(t, sub, uv, vel, 1.0, fragColor)) return;
    if(draw(t-0.3, sub, uv, vel, 0.8, fragColor)) return;
    if(draw(t-0.5, sub, uv, vel, 0.4, fragColor)) return;
    if(draw(t-0.7, sub, uv, vel, 0.1, fragColor)) return;
}

void mainImage( out vec4 fragColor, in vec2 fragCoord ) {
    float evSince = iTime-evTime;
    float evt = min(max(1.0-evSince,0.0) + evSince/4.0, 1.0);
    evt *= evt*evt;
	
		float time = iTime + seed;

    vec2 uv = (fragCoord.xy-iResolution.xy*.5) / RES;
    float rot_ang = noise(vec2(520,time/100.0))*4.0*PI-2.0*PI;
    uv *= mat2(cos(rot_ang), -sin(rot_ang), sin(rot_ang), cos(rot_ang));
    uv.y *= 1.0+sin(time/50.0)/3.0;
	
    vec2 seeded = uv*(2.0+sin(time/100.0)) + vec2(2,3)*time/10.0 + vec2(432,350);
    float d = noise(0.15*vec2(5.0,25.0)*seeded) + noise(seeded*13.0)/19.0;
    d = -1.0 + 2.0*d*d;
    d *= evt;
    d -= uv.y;
    float noise2 = exp(-abs(d));
    if (d<0.0) noise2 *= noise2;
    
    vec2 uv2 = vec2(uv.x, uv.y+d/3.0);
    vec4 noisevec = 3.5*vec4(uv2, uv2+vec2(150,200));
    noisevec += vec4(1,3,5,2)*time/10.0;
    
    vec2 pos = 2.0*vec2(noise(noisevec.xy),noise(noisevec.zw))-vec2(1,1);
    pos *= evt;
    noisevec += vec4(110,320,56,72);
    vec2 scale = 0.1*vec2(noise(noisevec.xy),noise(noisevec.zw));
    scale.y /= 5.0*d+1.0;
    
    vec2 r = (uv-pos)*scale;
    float radius = length(r)*(2.0-pow(evt,0.11));
    
    float ang = acos(r.x/radius);
    if (r.y<0.0) ang=2.0*PI-ang;
    float radnoise = noise(15.0*vec2(exp(radius),ang));
    
    float xd = 7.0+exp(-2.0*radius/length(scale));
    float lum = noise2*(0.8 + 0.5*(1.0-evt + 0.7*evt*noise(12.0*seeded))) + pow(radnoise,4.0) + (2.0-evt)*noise(uv*vec2(4.0,27.0) + vec2(132,110.0+3.0*d))/xd;
    
		fragColor = vec4(hsv2rgb(vec3(0.5-0.5*evt + (ang-rot_ang)/(2.0*PI)/10.0-0.07, 1.0-lum*lum*lum, lum)),1.0);

    if (ducks>0) {
      vec2 sub = iResolution.xy - imgSize;
      
      vec2 vel1 = vec2(3.0,-3.7)/20.0 * iResolution.xy;
      vec2 vel2 = vec2(-5.0,1.7)/20.0 * iResolution.xy;
      vec2 vel3 = vec2(0.9,2.7)/20.0 * iResolution.xy;
      
      draw1(iTime, sub, fragCoord, vel1, fragColor);
      draw1(iTime, sub, fragCoord, vel2, fragColor);
      draw1(iTime, sub, fragCoord, vel3, fragColor);
    }
}